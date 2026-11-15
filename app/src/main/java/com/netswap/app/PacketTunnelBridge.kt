package com.netswap.app

import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joins Android TUN packets, bidirectional quota gates and userspace WireGuard.
 *
 * This bridge is deliberately configured with already-validated session keys
 * and a gateway endpoint by its caller. It does not fetch credentials or invent
 * peer configuration. IPv4-only packet policy remains fail-closed.
 */
class PacketTunnelBridge(
    tunDevice: TunPacketDevice,
    engine: UserspaceWireGuardEngine,
    endpoint: InetSocketAddress,
    protectSocket: (java.net.DatagramSocket) -> Boolean,
    quotaBytes: Long,
    private val onGateResult: (PacketGateResult, TrafficDirection) -> Unit = { _, _ -> },
    private val onFailure: (String) -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val quota = SessionTrafficQuota(quotaBytes)

    private val udp = ProtectedUdpTransport(
        peer = endpoint,
        protectSocket = protectSocket
    )

    private val wireGuard = WireGuardDatagramPump(
        engine = engine,
        transport = udp,
        onTunnelPacket = { packet, version ->
            if (version != 4) {
                report(PacketGateResult.INVALID_PACKET, TrafficDirection.RECEIVED_FROM_RECEIVER)
            } else {
                val result = inboundGate.process(packet)
                report(result, TrafficDirection.RECEIVED_FROM_RECEIVER)
                if (result == PacketGateResult.QUOTA_EXHAUSTED ||
                    result == PacketGateResult.SESSION_REVOKED ||
                    result == PacketGateResult.SINK_REJECTED
                ) close()
            }
        },
        onFailure = { reason ->
            try { onFailure(reason) } finally { close() }
        },
        onWorkerExit = {
            if (!closed.get()) close()
        }
    )

    private val tun = TunPacketPump(
        device = tunDevice,
        onPacket = { packet ->
            val result = outboundGate.process(packet)
            report(result, TrafficDirection.SENT_TO_RECEIVER)
            if (result == PacketGateResult.QUOTA_EXHAUSTED ||
                result == PacketGateResult.SESSION_REVOKED ||
                result == PacketGateResult.SINK_REJECTED
            ) close()
        },
        onFailure = { reason ->
            try { onFailure(reason) } finally { close() }
        },
        onWorkerExit = {
            if (!closed.get()) close()
        }
    )

    private val outboundGate = PacketForwardingGate(
        quota = quota,
        direction = TrafficDirection.SENT_TO_RECEIVER,
        sink = PacketSink { packet ->
            wireGuard.sendTunnelPacket(packet)
            true
        }
    )

    private val inboundGate = PacketForwardingGate(
        quota = quota,
        direction = TrafficDirection.RECEIVED_FROM_RECEIVER,
        sink = PacketSink { packet ->
            tun.writePacket(packet)
            true
        }
    )

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

    /** Revokes both directions immediately and tears down transport resources. */
    fun revoke() {
        outboundGate.revoke()
        inboundGate.revoke()
        close()
    }

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
