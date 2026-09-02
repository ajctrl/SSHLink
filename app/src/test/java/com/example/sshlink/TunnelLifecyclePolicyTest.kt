package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLifecyclePolicyTest {
    @Test fun processRestartRunsOnlyWhenDesiredAndNotBlocked() {
        assertEquals(
            TunnelLifecyclePolicy.RestartDecision.RUN,
            TunnelLifecyclePolicy.restartDecision(desired = true, retryBlocked = false),
        )
        assertEquals(
            TunnelLifecyclePolicy.RestartDecision.STOP_NOT_DESIRED,
            TunnelLifecyclePolicy.restartDecision(desired = false, retryBlocked = false),
        )
        assertEquals(
            TunnelLifecyclePolicy.RestartDecision.STOP_RETRY_BLOCKED,
            TunnelLifecyclePolicy.restartDecision(desired = true, retryBlocked = true),
        )
    }

    @Test fun terminalBlockPreventsNetworkDrivenReconnect() {
        assertFalse(TunnelLifecyclePolicy.shouldAutomaticallyReconnect(true, true, false))
        assertFalse(TunnelLifecyclePolicy.shouldAutomaticallyReconnect(true, false, true))
        assertTrue(TunnelLifecyclePolicy.shouldAutomaticallyReconnect(true, false, false))
    }

}
