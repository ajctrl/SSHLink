package com.example.sshlink

/** Blocking power restrictions; battery-optimization exemption is optional. */
object AlwaysOnPowerPolicy {
    enum class BlockReason { BACKGROUND_RESTRICTED, LOW_POWER_STANDBY }

    fun blockReason(
        backgroundRestricted: Boolean,
        lowPowerStandbyEnabled: Boolean,
        lowPowerStandbyExempt: Boolean,
    ): BlockReason? = when {
        backgroundRestricted -> BlockReason.BACKGROUND_RESTRICTED
        lowPowerStandbyEnabled && !lowPowerStandbyExempt -> BlockReason.LOW_POWER_STANDBY
        else -> null
    }
}
