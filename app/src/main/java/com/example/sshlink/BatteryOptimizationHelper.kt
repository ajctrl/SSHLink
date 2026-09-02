package com.example.sshlink

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Standard Android power-policy checks required by the app's always-on contract. */
object BatteryOptimizationHelper {
    fun isIgnoringOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /**
     * Null means standard Android power policy allows an always-on socket/wake lock.
     * OEM-specific restrictions can still exist and must be covered by device testing.
     */
    fun blockingReason(context: Context): String? {
        val power = context.getSystemService(PowerManager::class.java)
        val lowPowerStandbyEnabled = Build.VERSION.SDK_INT >= 33 && power.isLowPowerStandbyEnabled
        // Android 13 exposes Low Power Standby but not an API that lets the app
        // prove its own exemption. Fail closed on API 33 when LPS is enabled.
        val lowPowerStandbyExempt = !lowPowerStandbyEnabled ||
            (Build.VERSION.SDK_INT >= 34 && power.isExemptFromLowPowerStandby)
        return when (AlwaysOnPowerPolicy.blockReason(
            backgroundRestricted = isBackgroundRestricted(context),
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
            lowPowerStandbyEnabled = lowPowerStandbyEnabled,
            lowPowerStandbyExempt = lowPowerStandbyExempt,
        )) {
            AlwaysOnPowerPolicy.BlockReason.BACKGROUND_RESTRICTED ->
                "Background usage is Restricted. Android can prevent foreground-service start/continuation and reboot restore while the app is in the background."
            AlwaysOnPowerPolicy.BlockReason.BATTERY_OPTIMIZATION ->
                "Battery optimization is active. Android Doze can suspend network access and ignore wake locks."
            AlwaysOnPowerPolicy.BlockReason.LOW_POWER_STANDBY ->
                "Low Power Standby is enabled and this app is not known to be exempt. Android disables network access and ignores wake locks while the device is non-interactive."
            null -> null
        }
    }

    fun openRelevantSettings(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val intent = when {
            isBackgroundRestricted(context) -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            !power.isIgnoringBatteryOptimizations(context.packageName) ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}
