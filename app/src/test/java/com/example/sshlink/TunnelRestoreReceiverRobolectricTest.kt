package com.example.sshlink

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TunnelRestoreReceiverRobolectricTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
    }

    @Test
    fun bootDoesNothingWhenTunnelWasStopped() {
        TunnelRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService())
    }

    @Test
    fun bootDoesNotBypassPersistedRetryBlock() {
        SettingsRepository(context).apply {
            setTunnelDesired(true)
            setRetryBlocked(true, "manual action required")
        }
        TunnelRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService())
    }

    @Test
    fun bootRestoresDesiredTunnelWhenRetryIsNotBlocked() {
        SettingsRepository(context).apply {
            setTunnelDesired(true)
            setRetryBlocked(false)
        }
        TunnelRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()
        assertEquals(TunnelService.ACTION_RESTORE, started.action)
    }
}
