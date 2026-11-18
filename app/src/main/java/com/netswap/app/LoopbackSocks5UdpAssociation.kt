package com.netswap.app

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One authenticated SOCKS5 UDP ASSOCIATE lifetime.
 *
 * The relay and client endpoint are loopback-only. The first valid loopback
 * datagram pins the client address/port; subsequent datagrams must match it.
 * Upstream UDP uses the supplied protected/network-bound socket opener.
 * Closing the TCP control connection tears down both UDP sockets and workers.
 */
class LoopbackSocks5UdpAssociation(
    private val upstream: UdpUpstreamOpener,
    private val maxDatagramBytes: Int = 65_507
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var relay: DatagramSocket? = null
    @Volatile private var upstreamSocket: DatagramSocket? = null
    @Volatile private var clientEndpoint: InetSocketAddress? = null
    @Volatile private var control: Socket? = null
    @Volatile private var clientWorker: Thread? = null
    @Volatile private var upstreamWorker: Thread? = null

    init { require(maxDatagramBytes in 512..65_507) }

    /** Starts an association and writes the SOCKS5 success reply to the control stream. */
    @Synchronized
    fun run(controlSocket: Socket, output: OutputStream) {
        check(!closed.get()) { "UDP association is closed" }
        check(relay == null) { "UDP association already started" }
        require(controlSocket.inetAddress.isLoopbackAddress) { "Control peer must be loopback" }
        control = controlSocket
        val localRelay = DatagramSocket(null)
        try {
            localRelay.reuseAddress = false
            localRelay.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            localRelay.soTimeout = 500
            val upstreamUdp = upstream.openUdp()
            upstreamUdp.soTimeout = 500
            relay = localRelay
            upstreamSocket = upstreamUdp
            // IPv4 loopback bind is explicit; return the actual ephemeral port.
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1,
                (localRelay.localPort ushr 8).toByte(), localRelay.localPort.toByte()))
            output.flush()
            clientWorker = Thread({ receiveClient(localRelay, upstreamUdp) }, "NetSwap-SOCKS-UDP-client").apply {
                isDaemon = true
                start()
            }
            upstreamWorker = Thread({ receiveUpstream(localRelay, upstreamUdp) }, "NetSwap-SOCKS-UDP-upstream").apply {
                isDaemon = true
                start()
            }
            // RFC 1928: the TCP control connection owns this association.
            val input = controlSocket.getInputStream()
            while (!closed.get()) {
                try {
                    if (input.read() < 0) break
                } catch (_: SocketException) { break }
                catch (_: Exception) { break }
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        } finally {
            close()
        }
    }

    private fun receiveClient(local: DatagramSocket, upstreamUdp: DatagramSocket) {
        val buffer = ByteArray(maxDatagramBytes)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                local.receive(packet)
                val source = packet.socketAddress as? InetSocketAddress ?: continue
                if (!source.address.isLoopbackAddress) continue
                val pinned = clientEndpoint
                if (pinned == null) {
                    synchronized(this) {
                        if (clientEndpoint == null) clientEndpoint = source
                    }
                }
                if (clientEndpoint != source) continue
                val encoded = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val decoded = Socks5UdpPacket.decode(encoded) ?: continue
                if (decoded.payload.size > maxDatagramBytes - 22) continue
                val outbound = DatagramPacket(decoded.payload, decoded.payload.size, decoded.destination)
                upstreamUdp.send(outbound)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                // Drop malformed or failed individual datagrams; keep association alive.
            }
        }
    }

    private fun receiveUpstream(local: DatagramSocket, upstreamUdp: DatagramSocket) {
        val buffer = ByteArray(maxDatagramBytes)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                upstreamUdp.receive(packet)
                val pinned = clientEndpoint ?: continue
                val source = packet.socketAddress as? InetSocketAddress ?: continue
                val encoded = Socks5UdpPacket.encode(
                    source, packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                ) ?: continue
                if (encoded.size > maxDatagramBytes) continue
                local.send(DatagramPacket(encoded, encoded.size, pinned))
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                // Drop an individual response; keep association alive.
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { relay?.close() } catch (_: Exception) { }
        try { upstreamSocket?.close() } catch (_: Exception) { }
        try { control?.close() } catch (_: Exception) { }
        listOfNotNull(clientWorker, upstreamWorker).forEach { worker ->
            if (worker !== Thread.currentThread()) {
                try { worker.join(500) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        relay = null
        upstreamSocket = null
        control = null
        clientWorker = null
        upstreamWorker = null
    }
}
