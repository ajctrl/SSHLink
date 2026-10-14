package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Test
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ScreenAwareSession
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReconnectPolicyTest {
    @Test fun jschHandshakeEofIsRetryable() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5000
            val worker = Executors.newSingleThreadExecutor()
            val peer = worker.submit {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("SSH-2.0-test-peer\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    socket.shutdownOutput()
                    // Drain outgoing packets to model an orderly EOF instead of a reset.
                    val buffer = ByteArray(4096)
                    while (socket.getInputStream().read(buffer) >= 0) { }
                }
            }
            val session = ScreenAwareSession(JSch(), "alice", "127.0.0.1", listener.localPort)
            SshSessionPolicy.apply(session)
            try {
                val failure = org.junit.Assert.assertThrows(com.jcraft.jsch.JSchException::class.java) {
                    session.connect(2000)
                }
                assertEquals("End of IO Stream Read", failure.cause?.message)
                assertEquals(ReconnectPolicy.FailureKind.RETRYABLE, ReconnectPolicy.classify(failure))
            } finally {
                session.disconnect()
                try {
                    peer.get(5, TimeUnit.SECONDS)
                } finally {
                    worker.shutdownNow()
                }
            }
        }
    }

    @Test fun unknownIoFailureStillFailsClosed() {
        assertEquals(ReconnectPolicy.FailureKind.TERMINAL,
            ReconnectPolicy.classify(IOException("unrecognized input error")))
    }

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
