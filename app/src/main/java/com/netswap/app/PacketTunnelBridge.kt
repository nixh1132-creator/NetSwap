package com.netswap.app

import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joins Android TUN packets, bidirectional quota gates and userspace WireGuard.
 * Requires validated session keys and gateway details from its caller.
 * IPv4-only policy remains fail-closed.
 */
class PacketTunnelBridge(
    tunDevice: TunPacketDevice,
    engine: UserspaceWireGuardEngine,
    endpoint: InetSocketAddress,
    protectSocket: (DatagramSocket) -> Boolean,
    quotaBytes: Long,
    private val onGateResult: (PacketGateResult, TrafficDirection) -> Unit = { _, _ -> },
    private val onFailure: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val quota = SessionTrafficQuota(quotaBytes)

    private val udp = ProtectedUdpTransport(endpoint, protectSocket)
    private lateinit var wireGuard: WireGuardDatagramPump
    private lateinit var tun: TunPacketPump
    private lateinit var outboundGate: PacketForwardingGate
    private lateinit var inboundGate: PacketForwardingGate

    init {
        // Pumps are constructed before gates; callbacks execute only after start().
        wireGuard = WireGuardDatagramPump(
            engine = engine,
            transport = udp,
            onTunnelPacket = { packet, version ->
                if (version != 4) {
                    report(PacketGateResult.INVALID_PACKET, TrafficDirection.RECEIVED_FROM_RECEIVER)
                } else {
                    val result = inboundGate.process(packet)
                    report(result, TrafficDirection.RECEIVED_FROM_RECEIVER)
                    if (mustStop(result)) close()
                }
            },
            onFailure = { reason ->
                try { onFailure(reason) } finally { close() }
            },
            onWorkerExit = { if (!closed.get()) close() }
        )

        tun = TunPacketPump(
            device = tunDevice,
            onPacket = { packet ->
                val result = outboundGate.process(packet)
                report(result, TrafficDirection.SENT_TO_RECEIVER)
                if (mustStop(result)) close()
            },
            onFailure = { reason ->
                try { onFailure(reason) } finally { close() }
            },
            onWorkerExit = { if (!closed.get()) close() }
        )

        outboundGate = PacketForwardingGate(
            quota = quota,
            direction = TrafficDirection.SENT_TO_RECEIVER,
            sink = PacketSink { packet ->
                wireGuard.sendTunnelPacket(packet)
                true
            }
        )
        inboundGate = PacketForwardingGate(
            quota = quota,
            direction = TrafficDirection.RECEIVED_FROM_RECEIVER,
            sink = PacketSink { packet ->
                tun.writePacket(packet)
                true
            }
        )
    }

    fun start() {
        check(!closed.get()) { "Packet tunnel bridge is closed" }
        try {
            wireGuard.start()
            tun.start()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun snapshot(): TrafficQuotaSnapshot = quota.snapshot()

    fun revoke() {
        outboundGate.revoke()
        inboundGate.revoke()
        close()
    }

    private fun mustStop(result: PacketGateResult): Boolean =
        result == PacketGateResult.QUOTA_EXHAUSTED ||
        result == PacketGateResult.SESSION_REVOKED ||
        result == PacketGateResult.SINK_REJECTED

    private fun report(result: PacketGateResult, direction: TrafficDirection) {
        try { onGateResult(result, direction) } catch (_: Exception) { }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            outboundGate.revoke()
            inboundGate.revoke()
            tun.close()
            wireGuard.close()
        }
    }
}
