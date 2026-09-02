package com.example.sshlink

import android.net.Network
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

class AndroidNetworkSocketFactory(
    private val network: Network?,
    private val connectTimeoutMs: Int,
    private val onResolved: (List<String>) -> Unit,
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val addresses: Array<InetAddress> = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        if (addresses.isEmpty()) throw UnknownHostException(host)
        onResolved(addresses.map { it.hostAddress ?: it.toString() })

        var lastError: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                socket.tcpNoDelay = true
                return socket
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IOException("Could not connect to $host:$port")
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
