package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException

class ReconnectPolicyTest {
    @Test fun backoffCapsAtSixtySeconds() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L),
            (1..8).map(ReconnectPolicy::retryDelaySeconds))
    }

    @Test fun authenticationFailureIsTerminal() {
        assertEquals(
            ReconnectPolicy.FailureKind.TERMINAL,
            ReconnectPolicy.classify(Exception("Auth fail")),
        )
    }

    @Test fun hostKeyChangeIsTerminal() {
        assertEquals(
            ReconnectPolicy.FailureKind.TERMINAL,
            ReconnectPolicy.classify(Exception("HostKey has been changed: example")),
        )
    }

    @Test fun timeoutIsRetryable() {
        assertEquals(
            ReconnectPolicy.FailureKind.RETRYABLE,
            ReconnectPolicy.classify(SocketTimeoutException("connect timed out")),
        )
    }

    @Test fun knownConnectionResetIsRetryable() {
        assertEquals(
            ReconnectPolicy.FailureKind.RETRYABLE,
            ReconnectPolicy.classify(SocketException("Connection reset by peer")),
        )
    }

    @Test fun unknownFailureIsTerminal() {
        assertEquals(
            ReconnectPolicy.FailureKind.TERMINAL,
            ReconnectPolicy.classify(Exception("unexpected protocol state")),
        )
    }

    @Test fun unknownSocketFailureIsTerminal() {
        assertEquals(
            ReconnectPolicy.FailureKind.TERMINAL,
            ReconnectPolicy.classify(SocketException("unclassified socket problem")),
        )
    }

    @Test
    fun jschRemoteCloseMessageIsRetryable() {
        assertEquals(
            ReconnectPolicy.FailureKind.RETRYABLE,
            ReconnectPolicy.classify(Exception("connection is closed by foreign host")),
        )
    }
}
