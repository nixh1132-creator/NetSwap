package com.netswap.app

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Drives one userspace WireGuard engine over one protected UDP transport.
 *
 * This joins protocol datagrams only. Decrypted TunnelPacket values are handed
 * to onTunnelPacket for a future policy/quota/TUN adapter; this class never
 * injects them into Android's VPN interface or forwards them to the internet.
 */
class WireGuardDatagramPump(
    private val engine: UserspaceWireGuardEngine,
    private val transport: ProtectedUdpTransport,
    private val onTunnelPacket: (ByteArray, Int) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val onWorkerExit: () -> Unit = {}
) : Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val engineLock = Any()
    @Volatile private var worker: Thread? = null

    /** Starts the UDP receive/timer loop once. */
    @Synchronized
    fun start() {
        check(!closed.get()) { "WireGuard pump is closed" }
        check(running.compareAndSet(false, true)) { "WireGuard pump already started" }
        worker = thread(name = "NetSwap-WireGuard-UDP", isDaemon = true) {
            try {
                while (running.get()) {
                    val datagram = transport.receive()
                    if (!running.get()) break
                    if (datagram != null) {
                        synchronized(engineLock) {
                            dispatch(engine.decrypt(datagram))
                        }
                    }
                    synchronized(engineLock) {
                        dispatch(engine.tick())
                    }
                }
            } catch (failure: Exception) {
                if (running.get()) {
                    running.set(false)
                    try { onFailure(failure.message ?: "WireGuard UDP loop failed") } catch (_: Exception) { }
                }
            } finally {
                releaseResources()
                try { onWorkerExit() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Encapsulates and sends one complete inner IP packet.
     * The caller remains responsible for validating the IP packet and quota.
     */
    fun sendTunnelPacket(packet: ByteArray) {
        check(!closed.get() && running.get()) { "WireGuard pump is not running" }
        require(packet.isNotEmpty() && packet.size <= 65_535) { "Invalid inner packet size" }
        synchronized(engineLock) {
            check(!closed.get() && running.get()) { "WireGuard pump is not running" }
            dispatch(engine.encrypt(packet))
        }
    }

    private fun dispatch(result: WireGuardEngineResult) {
        when (result) {
            is WireGuardEngineResult.NetworkDatagram -> transport.send(result.bytes)
            is WireGuardEngineResult.TunnelPacket -> onTunnelPacket(result.bytes, result.ipVersion)
            WireGuardEngineResult.NoPacket -> Unit
            is WireGuardEngineResult.Failure -> throw IllegalStateException(result.reason)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            running.set(false)
            transport.close() // unblocks a pending receive()
            val current = worker
            if (current !== Thread.currentThread()) {
                try { current?.join(1_500) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            releaseResources()
        }
    }

    @Synchronized
    private fun releaseResources() {
        if (resourcesReleased.compareAndSet(false, true)) {
            synchronized(engineLock) { engine.close() }
            transport.close()
        }
    }

    private val resourcesReleased = AtomicBoolean(false)
}
