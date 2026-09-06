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
                lowPowerStandbyEnabled = true,
                lowPowerStandbyExempt = false,
            ),
        )
    }

    @Test fun exemptionsAllowAlwaysOn() {
        assertNull(
            AlwaysOnPowerPolicy.blockReason(
                backgroundRestricted = false,
                lowPowerStandbyEnabled = true,
                lowPowerStandbyExempt = true,
            ),
        )
    }
}
