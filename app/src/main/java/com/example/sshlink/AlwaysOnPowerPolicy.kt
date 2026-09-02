package com.example.sshlink

/** Pure policy for deciding whether Android's standard power modes can satisfy always-on operation. */
object AlwaysOnPowerPolicy {
    enum class BlockReason { BACKGROUND_RESTRICTED, BATTERY_OPTIMIZATION, LOW_POWER_STANDBY }

    fun blockReason(
        backgroundRestricted: Boolean,
        ignoringBatteryOptimizations: Boolean,
        lowPowerStandbyEnabled: Boolean,
        lowPowerStandbyExempt: Boolean,
    ): BlockReason? = when {
        backgroundRestricted -> BlockReason.BACKGROUND_RESTRICTED
        !ignoringBatteryOptimizations -> BlockReason.BATTERY_OPTIMIZATION
        lowPowerStandbyEnabled && !lowPowerStandbyExempt -> BlockReason.LOW_POWER_STANDBY
        else -> null
    }
}
