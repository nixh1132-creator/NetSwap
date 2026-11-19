package com.netswap.app

import android.net.Network
import android.net.VpnService
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sender-side TCP endpoint for NSWP peer streams.
 *
 * OPEN_TCP payload format: SOCKS-style address:
 * ATYP(1=IPv4, 4=IPv6, 3=domain) + address/hostname + big-endian port(2).
 * Domain names are resolved using the provider's selected upstream Network.
 *
 * Each upstream socket is protected from the provider VPN and bound to the
 * provider's selected cellular Network. The endpoint is attached to the live
 * WebRTC stream router by NetSwapPeerProxyService.
 */
class PeerStreamSocketEndpoint(
    vpnService: VpnService,
    private val upstreamNetwork: Network?,
    private val quota: SessionTrafficQuota,
    private val sendData: (streamId: Int, payload: ByteArray) -> Boolean,
    private val sendClose: (streamId: Int) -> Boolean,
    private val sendError: (streamId: Int, message: String) -> Boolean,
    private val maxStreams: Int = DEFAULT_MAX_STREAMS
) : AutoCloseable {
    private val sockets = ProtectedUpstreamSockets(vpnService, upstreamNetwork)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val closed = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(
        2, MAX_WORKERS, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(MAX_QUEUED_TASKS),
        { task -> Thread(task, "netswap-peer-tcp").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    init {
        require(maxStreams in 1..DEFAULT_MAX_STREAMS)
    }

    /** Accepts a TCP OPEN without blocking the WebRTC callback thread. */
    fun openTcp(streamId: Int, destination: ByteArray): Boolean {
        if (closed.get() || streamId <= 0 || streams.size >= maxStreams) return false
        val stream = Stream()
        if (streams.putIfAbsent(streamId, stream) != null) return false
        return try {
            workers.execute {
                try {
                    // DNS and socket creation run off the WebRTC callback thread.
                    val address = decodeDestination(destination)
                    val socket = sockets.openTcp(address, CONNECT_TIMEOUT_MS)
                    synchronized(stream) {
                        if (stream.closed.get() || closed.get()) {
                            sockets.release(socket)
                            return@execute
                        }
                        stream.socket = socket
                        stream.output = socket.getOutputStream()
                        while (stream.pending.isNotEmpty()) {
                            val chunk = stream.pending.removeFirst()
                            stream.output!!.write(chunk)
                        }
                        stream.pendingBytes = 0
                        stream.output!!.flush()
                    }
                    val input = socket.getInputStream()
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (!closed.get() && !stream.closed.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (!quota.record(TrafficDirection.SENT_TO_RECEIVER, count.toLong())) {
                            sendError(streamId, "Session data quota exhausted.")
                            break
                        }
                        if (!sendData(streamId, buffer.copyOf(count))) break
                    }
                } catch (failure: Exception) {
                    if (!stream.closed.get() && !closed.get()) {
                        sendError(streamId, failure.message?.take(160) ?: "TCP upstream connection failed.")
                    }
                } finally {
                    closeStream(streamId, notifyPeer = true)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            streams.remove(streamId, stream)
            false
        }
    }

    /** Writes receiver-originated bytes to the protected upstream TCP socket. */
    fun writeTcp(streamId: Int, payload: ByteArray): Boolean {
        if (closed.get() || payload.isEmpty() || payload.size > MAX_PENDING_BYTES) return false
        val stream = streams[streamId] ?: return false
        if (!quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, payload.size.toLong())) {
            sendError(streamId, "Session data quota exhausted.")
            closeStream(streamId, notifyPeer = true)
            return false
        }
        synchronized(stream) {
            if (stream.closed.get()) return false
            val output = stream.output
            if (output == null) {
                if (stream.pendingBytes + payload.size > MAX_PENDING_BYTES) return false
                stream.pending.addLast(payload.copyOf())
                stream.pendingBytes += payload.size
                return true
            }
            return try {
                output.write(payload)
                output.flush()
                true
            } catch (_: IOException) {
                closeStream(streamId, notifyPeer = true)
                false
            }
        }
    }

    fun closeStream(streamId: Int, notifyPeer: Boolean = false) {
        val stream = streams.remove(streamId) ?: return
        if (!stream.closed.compareAndSet(false, true)) return
        synchronized(stream) {
            stream.pending.clear()
            stream.pendingBytes = 0
            stream.output = null
            stream.socket?.let(sockets::release)
            stream.socket = null
        }
        if (notifyPeer) sendClose(streamId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.keys.toList().forEach { closeStream(it) }
        workers.shutdownNow()
        sockets.close()
    }

    private fun decodeDestination(payload: ByteArray): InetSocketAddress {
        require(payload.isNotEmpty()) { "Missing TCP destination." }
        require(payload.size >= 4) { "TCP destination is too short." }
        val atyp = payload[0].toInt() and 0xff
        val port = ((payload[payload.size - 2].toInt() and 0xff) shl 8) or
            (payload[payload.size - 1].toInt() and 0xff)
        require(port in 1..65535) { "Invalid TCP destination port." }
        val address = when (atyp) {
            1 -> {
                require(payload.size == 1 + 4 + 2) { "Invalid IPv4 destination encoding." }
                InetAddress.getByAddress(payload.copyOfRange(1, 5))
            }
            4 -> {
                require(payload.size == 1 + 16 + 2) { "Invalid IPv6 destination encoding." }
                InetAddress.getByAddress(payload.copyOfRange(1, 17))
            }
            3 -> {
                require(payload.size >= 4) { "Invalid domain destination encoding." }
                val length = payload[1].toInt() and 0xff
                require(length in 1..253 && payload.size == 1 + 1 + length + 2) {
                    "Invalid domain destination encoding."
                }
                val host = payload.copyOfRange(2, 2 + length).toString(Charsets.US_ASCII)
                require(host.isNotBlank() && host.all { it.code in 33..126 }) { "Invalid destination hostname." }
                val resolved = upstreamNetwork?.getAllByName(host) ?: InetAddress.getAllByName(host)
                resolved.firstOrNull(::isPublicInternetAddress)
                    ?: throw IllegalArgumentException("Destination did not resolve to a public internet address.")
            }
            else -> throw IllegalArgumentException("Unsupported TCP destination type.")
        }
        require(isPublicInternetAddress(address)) { "Private or local network destinations are blocked." }
        return InetSocketAddress(address, port)
    }

    private fun isPublicInternetAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
            !address.isMulticastAddress

    private class Stream {
        val closed = AtomicBoolean(false)
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes = 0
        var socket: Socket? = null
        var output: java.io.OutputStream? = null
    }

    companion object {
        const val DEFAULT_MAX_STREAMS = 8
        private const val MAX_WORKERS = 8
        private const val MAX_QUEUED_TASKS = 64
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_BUFFER_BYTES = 12 * 1024
        private const val MAX_PENDING_BYTES = 64 * 1024
    }
}
