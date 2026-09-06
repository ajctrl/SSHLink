package com.example.sshlink

import android.Manifest
import android.app.AlertDialog
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityBatteryWarningTest {
    private fun prompt(activity: MainActivity, exempt: Boolean = false) {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(activity.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(activity.packageName, exempt)
        assertNull(BatteryOptimizationHelper.blockingReason(activity))
        MainActivity::class.java.getDeclaredMethod("requestStartWithBatteryWarning")
            .apply { isAccessible = true }.invoke(activity)
    }

    @Test fun warningAllowsStartingWithoutExemption() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        prompt(activity)
        assertNull(shadowOf(activity).nextStartedService)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Start anyway", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(TunnelService.ACTION_START, shadowOf(activity).nextStartedService.action)
        assertFalse(BatteryOptimizationHelper.isIgnoringOptimizations(activity))
        controller.destroy()
    }

    @Test fun cancellingWarningDoesNotStartService() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        prompt(activity)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedService)
        controller.destroy()
    }

    @Test fun exemptAppStartsWithoutWarning() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        prompt(activity, exempt = true)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(TunnelService.ACTION_START, shadowOf(activity).nextStartedService.action)
        controller.destroy()
    }
}
