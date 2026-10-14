package com.example.sshlink

import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import com.jcraft.jsch.JSchException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Network::class)
class VanishedNetworkShadow {
    @Implementation fun getAllByName(host: String): Array<InetAddress> = arrayOf(InetAddress.getByName("127.0.0.1"))
    @Implementation fun bindSocket(socket: Socket) {
        val cause = ErrnoException("Binding socket to network 101", OsConstants.ENONET)
        throw SocketException(cause.message).apply { initCause(cause) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [VanishedNetworkShadow::class])
class NetworkBindingFailureTest {
    @Test fun vanishedNetworkDuringSocketCreationRemainsRetryableThroughJschWrapper() {
        val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(101)
        val sockets = mutableListOf<Socket>()
        val factory = AndroidNetworkSocketFactory(network, 100, onCreated = { sockets += it }, onResolved = {})
        val failure = assertThrows(SocketException::class.java) { factory.createSocket("ssh.example.com", 22) }
        assertEquals(OsConstants.ENONET, (failure.cause as ErrnoException).errno)
        assertTrue(sockets.isNotEmpty())
        assertTrue(sockets.all { it.isClosed })
        assertEquals(ReconnectPolicy.FailureKind.RETRYABLE,
            ReconnectPolicy.classify(JSchException("Session.connect", failure)))
    }

    @Test fun permissionAndInvalidSocketErrorsRemainTerminal() {
        for (errno in listOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EINVAL)) {
            val failure = SocketException("Socket binding failed").apply {
                initCause(ErrnoException("bindSocket", errno))
            }
            assertEquals(ReconnectPolicy.FailureKind.TERMINAL, ReconnectPolicy.classify(failure))
        }
    }

    @Test fun terminalSecurityMessageTakesPrecedenceOverTransientCause() {
        val failure = SocketException("Host key has been changed").apply {
            initCause(ErrnoException("bindSocket", OsConstants.ENONET))
        }
        assertEquals(ReconnectPolicy.FailureKind.TERMINAL, ReconnectPolicy.classify(failure))
    }
}
