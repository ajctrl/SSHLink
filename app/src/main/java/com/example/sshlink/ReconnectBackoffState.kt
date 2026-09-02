package com.example.sshlink

/** Retry counter scoped to one connection generation. */
class ReconnectBackoffState {
    private val lock = Any()
    private var attempt = 0

    fun newGeneration() = synchronized(lock) { attempt = 0 }

    fun reset() = synchronized(lock) { attempt = 0 }

    fun nextAttempt(): Int = synchronized(lock) {
        attempt += 1
        attempt
    }

    fun currentAttempt(): Int = synchronized(lock) { attempt }
}
