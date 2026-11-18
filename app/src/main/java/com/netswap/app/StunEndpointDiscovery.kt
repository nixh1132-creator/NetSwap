package com.netswap.app

import android.net.Network
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs an RFC 5389 Binding exchange over a specifically selected Android
 * Network. The returned address is the NAT-observed UDP mapping, not a peer
 * address and not proof that inbound packets can traverse the carrier NAT.
 */
class StunEndpointDiscovery(
    private val network: Network,
    private val server: InetSocketAddress = InetSocketAddress("stun.l.google.com", 19302),
    private val timeoutMs: Int = 4_000,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) }
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    init {
        require(!server.isUnresolved && server.port in 1..65535) { "Invalid STUN server." }
        require(timeoutMs in 500..15_000) { "Invalid STUN timeout." }
    }

    @Synchronized
    fun discover(): InetSocketAddress {
        check(!closed.get()) { "STUN discovery is closed." }
        check(socket == null) { "STUN discovery can only run once." }
        val candidate = socketFactory()
        socket = candidate
        try {
            network.bindSocket(candidate)
            candidate.soTimeout = timeoutMs
            val request = StunBindingCodec.newRequest()
            candidate.send(DatagramPacket(request.bytes, request.bytes.size, server))
            val buffer = ByteArray(2_048)
            val response = DatagramPacket(buffer, buffer.size)
            candidate.receive(response)
            check(response.socketAddress == server) { "STUN response came from an unexpected server." }
            return StunBindingCodec.parseResponse(
                response.data.copyOfRange(response.offset, response.offset + response.length),
                request.transactionId
            )
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("STUN server did not respond. This network may block UDP.")
        } catch (failure: Exception) {
            if (failure is IllegalStateException) throw failure
            throw IllegalStateException("Could not discover this network's UDP mapping: ${failure.message ?: "network error"}")
        } finally {
            candidate.close()
            socket = null
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket?.close()
            socket = null
        }
    }
}
