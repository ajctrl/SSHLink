package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStateTest {
    @Test fun statusesHaveUserFacingNames() {
        assertEquals("Stopped", TunnelState.Status.STOPPED.displayName)
        assertEquals("Connecting", TunnelState.Status.CONNECTING.displayName)
        assertEquals("Connected", TunnelState.Status.CONNECTED.displayName)
        assertEquals("Reconnecting", TunnelState.Status.RECONNECT_WAIT.displayName)
        assertEquals("Connection error", TunnelState.Status.ERROR.displayName)
    }

    @Test fun logSnapshotVersionChangesOnlyWhenLogChanges() {
        val before = TunnelState.logsSnapshot()

        TunnelState.log("INFO", "snapshot version test")
        val after = TunnelState.logsSnapshot()

        assertTrue(after.version > before.version)
        assertEquals("snapshot version test", after.entries.last().message)
        assertEquals(after, TunnelState.logsSnapshot())
    }
}
