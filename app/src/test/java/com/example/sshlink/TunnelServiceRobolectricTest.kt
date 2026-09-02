package com.example.sshlink

import android.app.Service
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
}
