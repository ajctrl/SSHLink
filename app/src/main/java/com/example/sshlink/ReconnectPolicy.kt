package com.example.sshlink

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ReconnectPolicy {
    enum class FailureKind { RETRYABLE, TERMINAL }

    fun retryDelaySeconds(attempt: Int): Long {
        val normalized = attempt.coerceAtLeast(1)
        return (1L shl (normalized - 1).coerceAtMost(6)).coerceAtMost(60L)
    }

    /**
     * Retry only failures that are positively identified as transient network loss.
     * Unknown failures fail closed so configuration, crypto, protocol or security
     * errors cannot become a permanent reconnect loop.
     */
    fun classify(error: Throwable): FailureKind {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is EOFException -> return FailureKind.RETRYABLE

                is SecurityException,
                is IllegalArgumentException -> return FailureKind.TERMINAL

                is SocketException -> {
                    terminalMessage(current.message)?.let { return FailureKind.TERMINAL }
                    retryableMessage(current.message)?.let { return FailureKind.RETRYABLE }
                    return FailureKind.TERMINAL
                }
            }
            terminalMessage(current.message)?.let { return FailureKind.TERMINAL }
            retryableMessage(current.message)?.let { return FailureKind.RETRYABLE }
            current = current.cause
        }
        return FailureKind.TERMINAL
    }

    private fun retryableMessage(message: String?): Unit? {
        val msg = message.orEmpty().lowercase()
        val markers = listOf(
            "connection reset",
            "connection refused",
            "connection timed out",
            "connect timed out",
            "network is unreachable",
            "no route to host",
            "network is down",
            "software caused connection abort",
            "broken pipe",
            "socket closed",
            "socket is closed",
            "socket is not established",
            "connection is closed",
            "closed by foreign host",
            "remote host closed",
            "session is down",
            "end of file",
            "unexpected eof",
            "temporary failure in name resolution",
            "name or service not known",
        )
        return if (markers.any(msg::contains)) Unit else null
    }

    private fun terminalMessage(message: String?): Unit? {
        val msg = message.orEmpty().lowercase()
        val terminalMarkers = listOf(
            "auth fail",
            "authentication failed",
            "userauth fail",
            "permission denied (publickey)",
            "hostkey has been changed",
            "host key has been changed",
            "reject hostkey",
            "invalid privatekey",
            "invalid private key",
            "private key is encrypted",
            "algorithm negotiation fail",
            "algorithm negotiation failed",
            "portforwardingl",
            "port forwarding failed",
            "cannot be bound",
            "address already in use",
            "administratively prohibited",
            "unsupported key",
        )
        return if (terminalMarkers.any(msg::contains)) Unit else null
    }
}
