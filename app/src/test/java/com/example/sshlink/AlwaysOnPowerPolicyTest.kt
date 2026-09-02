package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlwaysOnPowerPolicyTest {
    @Test fun backgroundRestrictionBlocksAlwaysOn() {
        assertEquals(
            AlwaysOnPowerPolicy.BlockReason.BACKGROUND_RESTRICTED,
            AlwaysOnPowerPolicy.blockReason(
                backgroundRestricted = true,
                ignoringBatteryOptimizations = true,
                lowPowerStandbyEnabled = false,
                lowPowerStandbyExempt = true,
            ),
        )
    }

    @Test fun batteryOptimizationBlocksAlwaysOn() {
        assertEquals(
            AlwaysOnPowerPolicy.BlockReason.BATTERY_OPTIMIZATION,
            AlwaysOnPowerPolicy.blockReason(
                backgroundRestricted = false,
                ignoringBatteryOptimizations = false,
                lowPowerStandbyEnabled = false,
                lowPowerStandbyExempt = true,
            ),
        )
    }

    @Test fun nonExemptLowPowerStandbyBlocksAlwaysOn() {
        assertEquals(
            AlwaysOnPowerPolicy.BlockReason.LOW_POWER_STANDBY,
            AlwaysOnPowerPolicy.blockReason(
                backgroundRestricted = false,
                ignoringBatteryOptimizations = true,
                lowPowerStandbyEnabled = true,
                lowPowerStandbyExempt = false,
            ),
        )
    }

    @Test fun exemptionsAllowAlwaysOn() {
        assertNull(
            AlwaysOnPowerPolicy.blockReason(
                backgroundRestricted = false,
                ignoringBatteryOptimizations = true,
                lowPowerStandbyEnabled = true,
                lowPowerStandbyExempt = true,
            ),
        )
    }
}
