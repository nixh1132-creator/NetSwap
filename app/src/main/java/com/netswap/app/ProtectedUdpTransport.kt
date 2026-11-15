package com.netswap.app

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small protected UDP carrier for WireGuard datagrams.
 *
 * The caller must supply VpnService.protect(socket) as protectSocket. The socket
 * is protected before it is connected, preventing VPN routing loops. This class
 * carries only WireGuard UDP datagrams; it does not create a TUN interface,
 * configure a peer, forward user traffic, or enforce a sharing quota.
 */
class ProtectedUdpTransport(
    private val peer: InetSocketAddress,
    private val protectSocket: (DatagramSocket) -> Boolean,
    receiveTimeoutMs: Int = 1_000,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) }
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sendLock = Any()
    private val receiveLock = Any()
    private val socket: DatagramSocket

    init {
        require(!peer.isUnresolved) { "WireGuard peer address must be resolved" }
        require(peer.port in 1..65535) { "Invalid WireGuard peer port" }
        require(receiveTimeoutMs in 1..60_000) { "Invalid UDP receive timeout" }

        val candidate = socketFactory()
        try {
            candidate.bind(InetSocketAddress(0))
            check(protectSocket(candidate)) { "VPN could not protect the UDP socket" }
            candidate.soTimeout = receiveTimeoutMs
            candidate.connect(peer)
            socket = candidate
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    /** Sends one complete WireGuard UDP datagram to the configured peer. */
    fun send(datagram: ByteArray) {
        checkOpen()
        require(datagram.isNotEmpty() && datagram.size <= MAX_DATAGRAM_BYTES) {
            "Invalid WireGuard UDP datagram size"
        }
        synchronized(sendLock) {
            checkOpen()
            socket.send(DatagramPacket(datagram, datagram.size))
        }
    }

    /**
     * Receives one datagram from the connected peer.
     * Returns null on timeout; packets from other endpoints are filtered by connect().
     */
    fun receive(): ByteArray? {
        checkOpen()
        synchronized(receiveLock) {
            checkOpen()
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                return packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            } catch (_: SocketTimeoutException) {
                return null
            }
        }
    }

    private fun checkOpen() {
        check(!closed.get() && !socket.isClosed) { "UDP transport is closed" }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    companion object {
        private const val MAX_DATAGRAM_BYTES = 65_535
    }
}
