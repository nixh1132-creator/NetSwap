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
 * OPEN_UDP payload: ATYP + numeric IPv4/IPv6 address + big-endian port.
 * UDP_DATA payloads are raw datagram bodies; each stream has one fixed peer.
 */
class PeerStreamDatagramEndpoint(
    vpnService: VpnService,
    upstreamNetwork: Network?,
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
        val target = try { decode(encoded) } catch (_: Exception) { return false }
        val socket = try { sockets.openUdp() } catch (e: Exception) {
            sendError(id, e.message ?: "Could not open protected UDP socket")
            return false
        }
        try { socket.connect(target) } catch (_: Exception) {
            sockets.release(socket)
            return false
        }
        val stream = Stream(socket, target)
        if (streams.putIfAbsent(id, stream) != null) {
            sockets.release(socket)
            return false
        }
        return try {
            workers.execute {
                val buffer = ByteArray(MAX_DATAGRAM)
                try {
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
                } catch (e: Exception) {
                    if (!closed.get() && !stream.closed.get()) sendError(id, e.message ?: "UDP receive failed")
                } finally { closeStream(id) }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            closeStream(id); false
        }
    }

    fun sendUdp(id: Int, payload: ByteArray): Boolean {
        if (closed.get() || payload.isEmpty() || payload.size > MAX_DATAGRAM) return false
        val stream = streams[id] ?: return false
        if (!quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, payload.size.toLong())) {
            sendError(id, "Session data quota exhausted.")
            closeStream(id); return false
        }
        return try {
            val packet = DatagramPacket(payload, payload.size)
            stream.socket.send(packet)
            true
        } catch (_: Exception) { closeStream(id); false }
    }

    fun closeStream(id: Int) {
        val stream = streams.remove(id) ?: return
        if (stream.closed.compareAndSet(false, true)) sockets.release(stream.socket)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.keys.toList().forEach(::closeStream)
        workers.shutdownNow()
        sockets.close()
    }

    private fun decode(bytes: ByteArray): InetSocketAddress {
        require(bytes.isNotEmpty())
        val n = when (bytes[0].toInt() and 0xff) { 1 -> 4; 4 -> 16; else -> error("UDP requires numeric IP") }
        require(bytes.size == n + 3)
        val port = ((bytes[n + 1].toInt() and 255) shl 8) or (bytes[n + 2].toInt() and 255)
        require(port in 1..65535)
        return InetSocketAddress(InetAddress.getByAddress(bytes.copyOfRange(1, n + 1)), port)
    }

    private data class Stream(
        val socket: java.net.DatagramSocket,
        val target: InetSocketAddress,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    companion object { private const val MAX_DATAGRAM = 60 * 1024 }
}
