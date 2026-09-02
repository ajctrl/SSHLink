package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatePolicyTest {
    @Test
    fun retryBlockIsRestoredWhenProcessMemoryIsFresh() {
        val result = RuntimeStatePolicy.resolve(
            memoryStatus = TunnelState.Status.STOPPED,
            memoryDetail = "Stopped",
            memoryDesired = false,
            persistedDesired = true,
            retryBlocked = true,
            retryReason = "SSH host key changed",
        )
        assertEquals(TunnelState.Status.ERROR, result.status)
        assertEquals("SSH host key changed", result.detail)
        assertTrue(result.desired)
    }

    @Test
    fun liveMemoryStateWinsOverPersistedFallback() {
        val result = RuntimeStatePolicy.resolve(
            memoryStatus = TunnelState.Status.CONNECTED,
            memoryDetail = "Connected",
            memoryDesired = true,
            persistedDesired = true,
            retryBlocked = true,
            retryReason = "old error",
        )
        assertEquals(TunnelState.Status.CONNECTED, result.status)
        assertEquals("Connected", result.detail)
    }
}
