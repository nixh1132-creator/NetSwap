package com.netswap.app

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Small lifecycle-safe reader for a TUN packet device.
 *
 * Policy, quota checks, WireGuard encryption and packet injection are deliberately
 * supplied by the caller. This class does not install routes or start a VPN.
 */
class TunPacketPump(
    private val device: TunPacketDevice,
    private val onPacket: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val onWorkerExit: () -> Unit = {}
) : Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val deviceClosed = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(!closed.get()) { "TUN packet pump is closed" }
        check(running.compareAndSet(false, true)) { "TUN packet pump already started" }
        worker = thread(name = "NetSwap-TUN-reader", isDaemon = true) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            try {
                while (running.get()) {
                    val count = device.readPacket(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(count <= buffer.size) { "TUN device returned an invalid packet length" }
                    onPacket(buffer.copyOf(count))
                }
            } catch (failure: Exception) {
                if (running.get()) {
                    try { onFailure(failure.message ?: "TUN packet read failed") } catch (_: Exception) { }
                }
            } finally {
                running.set(false)
                closeDevice()
                try { onWorkerExit() } catch (_: Exception) { }
            }
        }
    }

    /** Writes one bounded packet while the reader is active. */
    fun writePacket(packet: ByteArray) {
        check(!closed.get() && running.get()) { "TUN packet pump is not running" }
        require(packet.isNotEmpty() && packet.size <= MAX_PACKET_SIZE) { "Invalid TUN packet size" }
        device.writePacket(packet.copyOf())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            running.set(false)
            closeDevice() // expected to unblock a pending descriptor read
            val current = worker
            if (current !== Thread.currentThread()) {
                try { current?.join(1_500) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun closeDevice() {
        if (deviceClosed.compareAndSet(false, true)) {
            try { device.close() } catch (_: Exception) { }
        }
    }

    companion object {
        const val MAX_PACKET_SIZE = 65_535
    }
}
