package com.example.sshlink

/**
 * Thread-safe ownership for active and in-flight connections.
 *
 * Each reconnect creates a new monotonically increasing generation and atomically
 * detaches every connection from the previous generation. Callers disconnect the
 * returned stale connections outside this class. A candidate may mutate trust or
 * become active only while it is still the registered in-flight connection for
 * the current generation.
 */
class ConnectionGenerationState<T : Any> {
    data class Invalidated<T : Any>(
        val generation: Long,
        val connections: List<T>,
    )

    private val lock = Any()
    private var generation = 0L
    private var active: T? = null
    private var inFlight: T? = null

    fun nextGeneration(): Invalidated<T> = synchronized(lock) {
        generation += 1
        Invalidated(generation, detachLocked())
    }

    fun invalidateIfCurrent(candidateGeneration: Long): Invalidated<T>? = synchronized(lock) {
        if (candidateGeneration != generation) return@synchronized null
        generation += 1
        Invalidated(generation, detachLocked())
    }

    fun invalidateAll(): Invalidated<T> = synchronized(lock) {
        generation += 1
        Invalidated(generation, detachLocked())
    }

    fun isCurrent(candidateGeneration: Long): Boolean = synchronized(lock) {
        candidateGeneration == generation
    }

    fun registerInFlight(candidateGeneration: Long, connection: T): Boolean = synchronized(lock) {
        if (candidateGeneration != generation) return@synchronized false
        if (inFlight != null && inFlight !== connection) return@synchronized false
        inFlight = connection
        true
    }

    /** Execute [block] only while [connection] still owns the current in-flight slot. */
    fun runIfInFlight(candidateGeneration: Long, connection: T, block: () -> Unit): Boolean = synchronized(lock) {
        if (candidateGeneration != generation || inFlight !== connection) return@synchronized false
        block()
        true
    }

    fun promote(candidateGeneration: Long, connection: T): Boolean = synchronized(lock) {
        if (candidateGeneration != generation || inFlight !== connection) return@synchronized false
        inFlight = null
        active = connection
        true
    }

    fun clear(connection: T) = synchronized(lock) {
        if (inFlight === connection) inFlight = null
        if (active === connection) active = null
    }

    fun active(): T? = synchronized(lock) { active }

    private fun detachLocked(): List<T> {
        val oldActive = active
        val oldInFlight = inFlight
        active = null
        inFlight = null
        return buildList {
            if (oldActive != null) add(oldActive)
            if (oldInFlight != null && oldInFlight !== oldActive) add(oldInFlight)
        }
    }
}
