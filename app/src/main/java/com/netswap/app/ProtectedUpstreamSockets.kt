package com.netswap.app

import android.net.Network
import android.net.VpnService
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Creates upstream sockets for a future sender-side userspace IP stack.
 *
 * Sockets are protected from this VpnService before they connect, preventing
 * their traffic from being captured by NetSwap's own VPN. When an Android
 * Network is supplied, the socket is bound to that network before connect.
 *
 * IMPORTANT: this is only a socket primitive. It does not translate IP packets
 * into TCP/UDP flows, forward traffic, or enforce a sharing quota.
 */
class ProtectedUpstreamSockets(
    private val vpnService: VpnService,
    private val upstreamNetwork: Network? = null
) : Closeable, TcpUpstreamOpener {
    private val sockets = LinkedHashSet<Closeable>()
    private var closed = false

    @Synchronized
    override fun openTcp(destination: InetSocketAddress, connectTimeoutMs: Int): Socket {
        check(!closed) { "Upstream socket factory is closed" }
        require(!destination.isUnresolved && destination.port in 1..65535) {
            "Invalid TCP destination"
        }
        require(connectTimeoutMs in 1..60_000) { "Invalid TCP connect timeout" }

        val socket = Socket()
        try {
            check(vpnService.protect(socket)) { "VPN could not protect upstream TCP socket" }
            upstreamNetwork?.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.connect(destination, connectTimeoutMs)
            sockets.add(socket)
            return socket
        } catch (failure: Throwable) {
            try { socket.close() } catch (_: Exception) { }
            throw failure
        }
    }

    @Synchronized
    fun openUdp(): DatagramSocket {
        check(!closed) { "Upstream socket factory is closed" }
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(0))
            check(vpnService.protect(socket)) { "VPN could not protect upstream UDP socket" }
            upstreamNetwork?.bindSocket(socket)
            sockets.add(socket)
            return socket
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    @Synchronized
    fun release(socket: Closeable) {
        sockets.remove(socket)
        try { socket.close() } catch (_: Exception) { }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        sockets.toList().forEach { socket ->
            try { socket.close() } catch (_: Exception) { }
        }
        sockets.clear()
    }
}
