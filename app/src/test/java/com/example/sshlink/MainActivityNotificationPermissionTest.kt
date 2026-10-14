package com.example.sshlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
class MainActivityNotificationPermissionTest {
    @Test fun grantingAfterRecreationStartsTunnelOnce() = checkRecreation(PackageManager.PERMISSION_GRANTED)

    @Test fun denyingAfterRecreationStillStartsTunnelOnce() = checkRecreation(PackageManager.PERMISSION_DENIED)

    private fun checkRecreation(grant: Int) {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.clearStartedServices()
        val original = Robolectric.buildActivity(MainActivity::class.java).create()
        MainActivity::class.java.getDeclaredMethod("requestNotificationPermissionThenStart")
            .apply { isAccessible = true }.invoke(original.get())
        assertNull(app.nextStartedService)
        val state = Bundle()
        original.saveInstanceState(state).destroy()
        val recreated = Robolectric.buildActivity(MainActivity::class.java).create(state)
        try {
            val permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            recreated.get().onRequestPermissionsResult(200, permissions, intArrayOf(grant))
            assertEquals(TunnelService.ACTION_START, app.nextStartedService?.action)
            recreated.get().onRequestPermissionsResult(200, permissions, intArrayOf(grant))
            assertNull(app.nextStartedService)
            val completedState = Bundle()
            recreated.saveInstanceState(completedState)
            val afterCompletion = Robolectric.buildActivity(MainActivity::class.java).create(completedState)
            try {
                afterCompletion.get().onRequestPermissionsResult(200, permissions, intArrayOf(grant))
                assertNull(app.nextStartedService)
            } finally {
                afterCompletion.destroy()
            }
        } finally {
            recreated.destroy()
        }
    }
}
