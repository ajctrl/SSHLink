package com.example.sshlink

import android.app.Service
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ScreenAwareSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TunnelServiceCommandRaceTest {
    private fun field(service: TunnelService, name: String): Any? = TunnelService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service)

    @Test fun startSurvivesOldPowerFailureCleanup() = checkRace(false, TunnelService.ACTION_START)
    @Test fun reconnectSurvivesOldPowerFailureCleanup() = checkRace(false, TunnelService.ACTION_RECONNECT)
    @Test fun startSurvivesOldTerminalFailureCleanup() = checkRace(true, TunnelService.ACTION_START)
    @Test fun reconnectSurvivesOldTerminalFailureCleanup() = checkRace(true, TunnelService.ACTION_RECONNECT)
    @Test fun terminalFailureWithoutNewCommandStillStopsService() = checkRace(true, null)
    @Test fun powerFailureWithoutNewCommandStillStopsService() = checkRace(false, null)

    private fun checkRace(terminal: Boolean, action: String?) {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        settings.setRetryBlocked(false)
        TunnelState.setDesired(false)
        TunnelState.setStatus(TunnelState.Status.STOPPED, "Stopped")
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        power.setLowPowerStandbySupported(false)
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().setAction(TunnelService.ACTION_START), 0, 1)
        // Install an established connection without running a real SSH handshake.
        TunnelService::class.java.getDeclaredField("screenInteractive").apply { isAccessible = true }.set(service, true)
        TunnelState.setStatus(TunnelState.Status.CONNECTED, "Connected")
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val session = object : ScreenAwareSession(JSch(), "test", "localhost", 22) {
            override fun disconnect() {
                closing.countDown()
                check(finishClose.await(5, TimeUnit.SECONDS))
            }
            override fun isConnected() = true
        }
        @Suppress("UNCHECKED_CAST")
        val connections = field(service, "connections") as ConnectionGenerationState<ScreenAwareSession>
        val gen = connections.nextGeneration().generation
        assertTrue(connections.registerInFlight(gen, session))
        assertTrue(connections.promote(gen, session))
        val threads = Executors.newFixedThreadPool(2)
        try {
            val failure = threads.submit {
                if (terminal) {
                    TunnelService::class.java.getDeclaredMethod("terminalFailure", Long::class.javaPrimitiveType,
                        String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(service, gen, "Authentication failed", 500)
                } else {
                    TunnelService::class.java.getDeclaredMethod("powerPolicyFailure", String::class.java)
                        .apply { isAccessible = true }.invoke(service, "Power restriction detected")
                }
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertEquals(TunnelState.Status.ERROR, TunnelState.status)
            if (action != null) {
                val commandThread = Thread.currentThread()
                val previousNotification = shadowOf(service).lastForegroundNotification
                val releaseAfterStart = threads.submit {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (commandThread.state != Thread.State.BLOCKED &&
                        field(service, "runtimeRetryBlocked") == true && System.nanoTime() < deadline) Thread.yield()
                    check(System.nanoTime() < deadline)
                    // Promotion must happen before the command waits on cleanup.
                    assertNotSame(previousNotification, shadowOf(service).lastForegroundNotification)
                    finishClose.countDown()
                }
                val code = service.onStartCommand(Intent().setAction(action), 0, 2)
                releaseAfterStart.get(5, TimeUnit.SECONDS)
                assertEquals(Service.START_STICKY, code)
            } else {
                finishClose.countDown()
            }
            failure.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(settings.isTunnelDesired())
            if (action != null) {
                assertEquals(TunnelState.Status.PAUSED, TunnelState.status)
                assertFalse(settings.isRetryBlocked())
                assertFalse(shadowOf(service).isStoppedBySelf)
                assertFalse(shadowOf(service).isForegroundStopped)
                assertEquals(true, field(service, "powerSignalReceiverRegistered"))
                assertEquals(true, field(service, "callbackRegistered"))
            } else {
                assertEquals(TunnelState.Status.ERROR, TunnelState.status)
                assertTrue(settings.isRetryBlocked())
                assertTrue(shadowOf(service).isStoppedBySelf)
                assertTrue(shadowOf(service).isForegroundStopped)
                assertEquals(1, shadowOf(service).stopSelfId)
                assertEquals(false, field(service, "powerSignalReceiverRegistered"))
            }
        } finally {
            finishClose.countDown()
            threads.shutdownNow()
            threads.awaitTermination(5, TimeUnit.SECONDS)
            controller.destroy()
        }
    }
}
