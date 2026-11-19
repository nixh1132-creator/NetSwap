package com.netswap.app

import android.net.Network
import android.net.VpnService
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sender-side protected UDP endpoint for one peer UDP stream.
 * OPEN_UDP payload: ATYP + IPv4/IPv6/domain address + big-endian port.
 * UDP_DATA payloads are raw datagram bodies; each stream has one fixed peer.
 */
class PeerStreamDatagramEndpoint(
    vpnService: VpnService,
    private val upstreamNetwork: Network?,
    private val quota: SessionTrafficQuota,
    private val sendData: (Int, ByteArray) -> Boolean,
    private val sendError: (Int, String) -> Boolean,
    private val maxStreams: Int = 8
) : Closeable {
    private val sockets = ProtectedUpstreamSockets(vpnService, upstreamNetwork)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val workers = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "netswap-peer-udp").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    init { require(maxStreams in 1..32) }

    fun openUdp(id: Int, encoded: ByteArray): Boolean {
        if (closed.get() || id <= 0 || streams.size >= maxStreams) return false
        val stream = Stream()
        if (streams.putIfAbsent(id, stream) != null) return false
        return try {
            workers.execute {
                try {
                    // Provider-side DNS and UDP socket setup must not block WebRTC callbacks.
                    val target = decode(encoded)
                    val socket = sockets.openUdp()
                    try {
                        socket.connect(target)
                        synchronized(stream) {
                            if (closed.get() || stream.closed.get()) {
                                sockets.release(socket)
                                return@execute
                            }
                            stream.socket = socket
                            while (stream.pending.isNotEmpty()) {
                                val body = stream.pending.removeFirst()
                                socket.send(DatagramPacket(body, body.size))
                            }
                            stream.pendingBytes = 0
                        }
                        val buffer = ByteArray(MAX_DATAGRAM)
                        while (!closed.get() && !stream.closed.get()) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.soTimeout = 1000
                            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                            if (!quota.record(TrafficDirection.SENT_TO_RECEIVER, packet.length.toLong())) {
                                sendError(id, "Session data quota exhausted.")
                                break
                            }
                            if (!sendData(id, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))) break
                        }
                    } catch (failure: Exception) {
                        sockets.release(socket)
                        throw failure
                    }
                } catch (failure: Exception) {
                    if (!closed.get() && !stream.closed.get()) {
                        sendError(id, failure.message ?: "UDP upstream connection failed")
                    }
                } finally { closeStream(id) }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            closeStream(id)
            false
        }
    }

    fun sendUdp(id: Int, payload: ByteArray): Boolean {
        if (closed.get() || payload.isEmpty() || payload.size > MAX_DATAGRAM) return false
        val stream = streams[id] ?: return false
        if (!quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, payload.size.toLong())) {
            sendError(id, "Session data quota exhausted.")
            closeStream(id)
            return false
        }
        synchronized(stream) {
            if (stream.closed.get()) return false
            val socket = stream.socket
            if (socket == null) {
                if (stream.pendingBytes + payload.size > MAX_PENDING_BYTES) return false
                stream.pending.addLast(payload.copyOf())
                stream.pendingBytes += payload.size
                return true
            }
            return try {
                socket.send(DatagramPacket(payload, payload.size))
                true
            } catch (_: Exception) {
                closeStream(id)
                false
            }
        }
    }

    fun closeStream(id: Int) {
        val stream = streams.remove(id) ?: return
        if (stream.closed.compareAndSet(false, true)) synchronized(stream) {
            stream.pending.clear()
            stream.pendingBytes = 0
            stream.socket?.let(sockets::release)
            stream.socket = null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.keys.toList().forEach(::closeStream)
        workers.shutdownNow()
        sockets.close()
    }

    private fun decode(bytes: ByteArray): InetSocketAddress {
        require(bytes.size >= 4) { "UDP destination is too short" }
        val atyp = bytes[0].toInt() and 0xff
        val address: InetAddress
        when (atyp) {
            1 -> {
                require(bytes.size == 1 + addressLength + 2) { "Invalid UDP IPv4 destination" }
                address = InetAddress.getByAddress(bytes.copyOfRange(1, 5))
            }
            4 -> {
                require(bytes.size == 1 + addressLength + 2) { "Invalid UDP IPv6 destination" }
                address = InetAddress.getByAddress(bytes.copyOfRange(1, 17))
            }
            3 -> {
                val length = bytes[1].toInt() and 0xff
                require(length in 1..253 && bytes.size == 2 + length + 2) { "Invalid UDP domain destination" }
                val host = bytes.copyOfRange(2, 2 + length).toString(Charsets.US_ASCII)
                require(host.all { it.code in 33..126 }) { "Invalid UDP hostname" }
                val network = upstreamNetwork ?: error("Provider upstream network is unavailable for DNS")
                address = network.getAllByName(host).firstOrNull { isPublicDestination(it) }
                    ?: error("UDP hostname did not resolve to a public address")
            }
            else -> error("Unsupported UDP address type")
        }
        val portOffset = bytes.size - 2
        val port = ((bytes[portOffset].toInt() and 255) shl 8) or (bytes[portOffset + 1].toInt() and 255)
        require(port in 1..65535) { "Invalid UDP destination port" }
        require(isPublicDestination(address)) { "Private or local UDP destinations are blocked" }
        return InetSocketAddress(address, port)
    }

    private fun isPublicDestination(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
            !address.isMulticastAddress

    private class Stream {
        val closed = AtomicBoolean(false)
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes = 0
        var socket: java.net.DatagramSocket? = null
    }

    companion object {
        private const val MAX_DATAGRAM = PeerStreamFrame.MAX_PAYLOAD_BYTES
        private const val MAX_PENDING_BYTES = 64 * 1024
    }
}
