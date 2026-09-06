package com.example.sshlink

import android.app.Service
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ScreenAwareSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TunnelServiceRobolectricTest {
    private lateinit var context: Context
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().clear().commit()
        TunnelState.setDesired(false)
        TunnelState.setStatus(TunnelState.Status.STOPPED, "Stopped")
        TunnelState.setActiveForwards(emptyList())
        powerManager = context.getSystemService(PowerManager::class.java)
        val shadowPower = shadowOf(powerManager)
        shadowPower.setIgnoringBatteryOptimizations(context.packageName, false)
        shadowPower.setLowPowerStandbySupported(false)
        shadowPower.setIsInteractive(true)
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowPowerManager.clearWakeLocks()
    }

    @Test
    fun stopCommandClearsDurableDesiredAndRetryBlock() {
        val repository = SettingsRepository(context)
        repository.setTunnelDesired(true)
        repository.setRetryBlocked(true, "test block")

        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(
            Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_STOP),
            0,
            1,
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(repository.isTunnelDesired())
        assertFalse(repository.isRetryBlocked())
        assertEquals(TunnelState.Status.STOPPED, TunnelState.status)
        controller.destroy()
    }

    @Test
    fun stickyRestartWithRetryBlockRestoresErrorWithoutClearingDesired() {
        val repository = SettingsRepository(context)
        repository.setTunnelDesired(true)
        repository.setRetryBlocked(true, "SSH host key changed")

        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(null, 0, 2)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(repository.isTunnelDesired())
        assertTrue(repository.isRetryBlocked())
        assertEquals(TunnelState.Status.ERROR, TunnelState.status)
        assertEquals("SSH host key changed", TunnelState.detail)
        controller.destroy()
    }

    @Test
    fun startAlwaysPromotesForegroundBeforeFailingClosedOnPowerPolicy() {
        shadowOf(context.getSystemService(android.app.ActivityManager::class.java)).setBackgroundRestricted(true)
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val shadowService = shadowOf(service)

        val result = service.onStartCommand(
            Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_START),
            0,
            3,
        )

        assertEquals(Service.START_STICKY, result)
        // stopForeground(REMOVE) clears Robolectric's notification reference.
        // The retained ID plus the stopped flag prove that startForeground()
        // ran before the power-policy failure removed the notification.
        assertTrue(shadowService.lastForegroundNotificationId > 0)
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(SettingsRepository(context).isRetryBlocked())
        assertEquals(TunnelState.Status.ERROR, TunnelState.status)
        assertTrue(shadowService.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun allowedStartAcquiresWakeLockAndStopReleasesIt() {
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)

        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(
            Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_START),
            0,
            4,
        )

        assertEquals(Service.START_STICKY, result)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wakeLock)
        assertTrue(shadowOf(wakeLock).timesHeld >= 1)

        service.onStartCommand(
            Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_STOP),
            0,
            5,
        )
        assertFalse(wakeLock.isHeld)
        controller.destroy()
    }

    @Test
    fun connectedNotificationShowsUserFacingStatus() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        TunnelState.setStatus(TunnelState.Status.CONNECTED, "Connected")
        val buildNotification = TunnelService::class.java
            .getDeclaredMethod("buildNotification")
            .apply { isAccessible = true }

        val notification = buildNotification.invoke(service) as Notification

        assertEquals("SSHLink — Connected", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Connected", notification.extras.getString(Notification.EXTRA_TEXT))
        controller.destroy()
    }

    private class FakeSession : ScreenAwareSession(JSch(), "test", "localhost", 22) {
        var connected = true
        var disconnects = 0
        var probes = 0
        @Volatile var reply: Runnable? = null
        val probeStarted = CountDownLatch(1)
        override fun isConnected() = connected
        override fun disconnect() { connected = false; disconnects++ }
        override fun prepareProbe(onReply: Runnable) { reply = onReply }
        override fun sendKeepAliveMsg() {
            probes++
            probeStarted.countDown()
        }
    }

    private fun field(service: TunnelService, name: String): Any? =
        TunnelService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun screen(service: TunnelService, interactive: Boolean) {
        TunnelService::class.java.getDeclaredMethod("updateScreenState", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, interactive)
    }

    private fun installSession(service: TunnelService): FakeSession {
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)
        SettingsRepository(context).setTunnelDesired(true)
        TunnelState.setDesired(true)
        TunnelState.setStatus(TunnelState.Status.CONNECTED, "Connected")
        TunnelState.setActiveForwards(listOf("127.0.0.1:1234 -> localhost:22"))
        @Suppress("UNCHECKED_CAST")
        val connections = field(service, "connections") as ConnectionGenerationState<ScreenAwareSession>
        val session = FakeSession().apply { setServerAliveInterval(30_000) }
        val gen = connections.nextGeneration().generation
        assertTrue(connections.registerInFlight(gen, session))
        assertTrue(connections.promote(gen, session))
        TunnelService::class.java.getDeclaredField("keepAliveIntervalMs")
            .apply { isAccessible = true }.set(service, 30_000)
        TunnelService::class.java.getDeclaredField("sessionNetwork")
            .apply { isAccessible = true }.set(service,
                context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        return session
    }

    @Test fun screenOffRetainsSessionAndStopsMaintenance() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, true)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wakeLock.isHeld)

        screen(service, false)

        assertEquals(0, session.disconnects)
        assertEquals(0, session.serverAliveInterval)
        assertEquals(TunnelState.Status.PAUSED, TunnelState.status)
        assertFalse(wakeLock.isHeld)
        assertEquals(null, field(service, "monitorFuture"))
        assertEquals(null, field(service, "reconnectFuture"))
        assertFalse(TunnelState.activeForwards.isEmpty())
        controller.destroy()
    }

    @Test fun quickWakeReusesSessionOnlyAfterReplyAndUnlockDoesNotDuplicateProbe() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, false)
        screen(service, true)
        assertTrue(session.probeStarted.await(2, TimeUnit.SECONDS))
        screen(service, true) // USER_PRESENT after SCREEN_ON
        assertEquals(1, session.probes)
        assertEquals(TunnelState.Status.CONNECTING, TunnelState.status)
        assertEquals(30_000, session.serverAliveInterval)

        session.reply!!.run()

        assertEquals(TunnelState.Status.CONNECTED, TunnelState.status)
        assertEquals(0, session.disconnects)
        assertEquals(null, field(service, "probeFuture"))
        controller.destroy()
    }

    @Test fun lateProbeReplyCannotWakeSleepingOrStoppedTunnel() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, false)
        screen(service, true)
        assertTrue(session.probeStarted.await(2, TimeUnit.SECONDS))
        val lateReply = session.reply!!
        val deadline = field(service, "probeFuture") as ScheduledFuture<*>
        screen(service, false)
        lateReply.run()
        assertTrue(deadline.isCancelled)
        assertEquals(TunnelState.Status.PAUSED, TunnelState.status)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        service.onStartCommand(Intent().setAction(TunnelService.ACTION_STOP), 0, 8)
        screen(service, true)
        lateReply.run()
        assertEquals(TunnelState.Status.STOPPED, TunnelState.status)
        assertEquals(1, session.probes)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        controller.destroy()
    }

    @Test fun silentSessionIsReplacedAfterProbeDeadline() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, false)
        screen(service, true)
        assertTrue(session.probeStarted.await(2, TimeUnit.SECONDS))
        val deadline = field(service, "probeFuture") as ScheduledFuture<*>
        try { deadline.get(7, TimeUnit.SECONDS) } catch (_: java.util.concurrent.CancellationException) {
            // Reconnect cancels the deadline that triggered it. Wait for its lock to be released.
        }
        val lock = field(service, "lock")!!
        synchronized(lock) { assertEquals(1, session.disconnects) }
        assertTrue(TunnelState.activeForwards.isEmpty())
        controller.destroy()
    }

    @Test fun startWithoutExemptionWhileScreenOffWaitsWithoutAcquiringWakeLock() {
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(powerManager).setIsInteractive(false)
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().setAction(TunnelService.ACTION_START), 0, 9)
        assertEquals(TunnelState.Status.PAUSED, TunnelState.status)
        assertEquals(null, field(service, "wakeLock"))
        assertEquals(null, field(service, "monitorFuture"))
        assertEquals(null, field(service, "reconnectFuture"))
        controller.destroy()
    }

    @Test fun explicitStartCanRecoverFromPreviousError() {
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)
        SettingsRepository(context).setRetryBlocked(true, "Old failure")
        TunnelState.setStatus(TunnelState.Status.ERROR, "Old failure")
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().setAction(TunnelService.ACTION_START), 0, 10)
        assertFalse(TunnelState.detail == "Old failure")
        controller.destroy()
    }

    @Test fun networkEventsWhileAsleepDoNotDisconnectOrScheduleRetry() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, false)
        val callback = field(service, "networkCallback") as android.net.ConnectivityManager.NetworkCallback
        callback.onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(999))
        callback.onLost(org.robolectric.shadows.ShadowNetwork.newInstance(999))
        assertEquals(0, session.disconnects)
        assertEquals(null, field(service, "reconnectFuture"))
        assertEquals(TunnelState.Status.PAUSED, TunnelState.status)
        controller.destroy()
    }

    @Test fun changedNetworkOnWakeReplacesSessionWithoutProbingOldRoute() {
        val controller = Robolectric.buildService(TunnelService::class.java).create()
        val service = controller.get()
        val session = installSession(service)
        screen(service, false)
        TunnelService::class.java.getDeclaredField("sessionNetwork")
            .apply { isAccessible = true }.set(service, org.robolectric.shadows.ShadowNetwork.newInstance(999))
        screen(service, true)
        assertEquals(1, session.disconnects)
        assertEquals(0, session.probes)
        controller.destroy()
    }
}
