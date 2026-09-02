package com.example.sshlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores a user-requested persistent tunnel after reboot or app replacement. */
class TunnelRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "device reboot completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app package replaced"
            else -> return
        }

        val settings = SettingsRepository(context)
        if (!settings.isTunnelDesired() || settings.isRetryBlocked()) return

        try {
            TunnelService.restorePersisted(context, reason)
        } catch (e: RuntimeException) {
            // Keep desired=true: background-start policy/OEM restrictions can be transient.
            TunnelState.log("ERROR", "Could not restore SSH tunnel after $reason: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
