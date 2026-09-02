package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffStateTest {
    @Test
    fun newGenerationResetsBackoffWithoutAffectingRetriesWithinGeneration() {
        val state = ReconnectBackoffState()
        assertEquals(1, state.nextAttempt())
        assertEquals(2, state.nextAttempt())
        state.newGeneration()
        assertEquals(0, state.currentAttempt())
        assertEquals(1, state.nextAttempt())
    }
}
