package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGenerationStateTest {
    private class FakeConnection(val name: String)

    @Test fun nextGenerationDetachesActiveAndInFlightConnections() {
        val state = ConnectionGenerationState<FakeConnection>()
        val first = FakeConnection("first")
        val gen1 = state.nextGeneration().generation
        assertTrue(state.registerInFlight(gen1, first))
        assertTrue(state.promote(gen1, first))
        assertSame(first, state.active())

        val second = FakeConnection("second")
        val invalidated = state.nextGeneration()
        assertEquals(listOf(first), invalidated.connections)
        assertNull(state.active())
        assertTrue(state.registerInFlight(invalidated.generation, second))
    }

    @Test fun obsoleteGenerationCannotRegisterPromoteOrMutateTrust() {
        val state = ConnectionGenerationState<FakeConnection>()
        val oldGen = state.nextGeneration().generation
        val old = FakeConnection("old")
        assertTrue(state.registerInFlight(oldGen, old))

        val newGen = state.nextGeneration().generation
        var trustCommitted = false
        assertFalse(state.runIfInFlight(oldGen, old) { trustCommitted = true })
        assertFalse(trustCommitted)
        assertFalse(state.promote(oldGen, old))

        val current = FakeConnection("current")
        assertTrue(state.registerInFlight(newGen, current))
        assertTrue(state.runIfInFlight(newGen, current) { trustCommitted = true })
        assertTrue(trustCommitted)
        assertTrue(state.promote(newGen, current))
    }

    @Test fun obsoleteTerminalFailureCannotInvalidateNewGeneration() {
        val state = ConnectionGenerationState<FakeConnection>()
        val oldGen = state.nextGeneration().generation
        val newGen = state.nextGeneration().generation

        assertNull(state.invalidateIfCurrent(oldGen))
        assertTrue(state.isCurrent(newGen))
        val invalidated = state.invalidateIfCurrent(newGen)
        assertTrue(invalidated != null)
        assertFalse(state.isCurrent(newGen))
    }
}
