package com.netswap.app

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional packet copier between Android's VPN TUN and Hev's datagram
 * socketpair endpoint. Each direction has one reader, preserving packet
 * boundaries and avoiding competing reads from the TUN descriptor.
 */
class HevTunPacketRelay(
    private val androidTun: TunPacketDevice,
    private val hevSide: TunPacketDevice,
    private val onFailure: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "netswap-hev-packet-relay").apply { isDaemon = true }
    }

    fun start() {
        check(!closed.get()) { "Packet relay is closed." }
        workers.execute { pump(androidTun, hevSide, "TUN-to-Hev") }
        workers.execute { pump(hevSide, androidTun, "Hev-to-TUN") }
    }

    private fun pump(source: TunPacketDevice, destination: TunPacketDevice, label: String) {
        val buffer = ByteArray(TunPacketPump.MAX_PACKET_SIZE)
        try {
            while (!closed.get()) {
                val count = source.readPacket(buffer)
                if (count <= 0) {
                    if (!closed.get()) error("$label packet stream ended.")
                    return
                }
                if (count > buffer.size) error("$label packet exceeded MTU buffer.")
                destination.writePacket(buffer.copyOf(count))
            }
        } catch (failure: Exception) {
            if (!closed.get()) {
                try { onFailure("$label failed: ${failure.message ?: "packet I/O error"}") }
                finally { close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdownNow()
        // Closing both packet endpoints unblocks their blocking reads.
        runCatching { androidTun.close() }
        runCatching { hevSide.close() }
    }
}
