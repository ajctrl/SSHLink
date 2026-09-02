package com.example.sshlink

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object TunnelState {
    enum class Status(val displayName: String) {
        STOPPED("Stopped"),
        CONNECTING("Connecting"),
        CONNECTED("Connected"),
        RECONNECT_WAIT("Reconnecting"),
        ERROR("Connection error"),
    }

    data class LogEntry(val timeMs: Long, val level: String, val message: String) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timeMs))
            return "$time [$level] $message"
        }
    }

    data class LogSnapshot(val version: Long, val entries: List<LogEntry>)

    private val lock = Any()
    private val logs = ArrayDeque<LogEntry>()
    private var logsVersion = 0L

    @Volatile var status: Status = Status.STOPPED
        private set
    @Volatile var detail: String = "Stopped"
        private set
    @Volatile var desiredRunning: Boolean = false
        private set
    @Volatile var activeForwards: List<String> = emptyList()
        private set

    fun setDesired(value: Boolean) {
        desiredRunning = value
    }

    fun setStatus(value: Status, detailText: String) {
        status = value
        detail = detailText
    }

    fun setActiveForwards(items: List<String>) {
        activeForwards = items.toList()
    }

    fun log(level: String, message: String, limit: Int = 500) {
        synchronized(lock) {
            logs.addLast(LogEntry(System.currentTimeMillis(), level, message))
            while (logs.size > limit.coerceIn(100, 5000)) logs.removeFirst()
            logsVersion += 1
        }
    }

    fun logsSnapshot(): LogSnapshot = synchronized(lock) {
        LogSnapshot(logsVersion, logs.toList())
    }
}
