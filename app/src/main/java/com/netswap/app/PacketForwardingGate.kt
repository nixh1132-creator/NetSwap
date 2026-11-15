package com.netswap.app

/**
 * Fail-closed boundary between packet inspection/quota accounting and a future
 * forwarding implementation. This prototype deliberately has no socket or TUN
 * integration; a reviewed transport must be supplied as the sink.
 */
fun interface PacketSink {
    /** Returns true only when the sink accepted the complete packet. */
    fun send(packet: ByteArray): Boolean
}

enum class PacketGateResult {
    FORWARDED,
    INVALID_PACKET,
    UNSUPPORTED_FRAGMENT,
    QUOTA_EXHAUSTED,
    SESSION_REVOKED,
    SINK_REJECTED
}

class PacketForwardingGate(
    private val quota: SessionTrafficQuota,
    private val direction: TrafficDirection,
    private val sink: PacketSink
) {
    private var revoked = false

    /**
     * Inspects and accounts a complete IPv4 packet before handing a bounded copy
     * to the sink. Fragmented packets are rejected until fragment-safe forwarding
     * and accounting are implemented. Failed sink sends remain charged (fail closed).
     */
    @Synchronized
    fun process(packet: ByteArray): PacketGateResult {
        if (revoked || !quota.snapshot().active) return PacketGateResult.SESSION_REVOKED

        val info = try {
            Ipv4PacketInspector.inspect(packet)
        } catch (_: IllegalArgumentException) {
            return PacketGateResult.INVALID_PACKET
        }

        if (info.isFragmented) return PacketGateResult.UNSUPPORTED_FRAGMENT
        if (!quota.record(direction, info.totalLength.toLong())) {
            return PacketGateResult.QUOTA_EXHAUSTED
        }

        val boundedPacket = packet.copyOf(info.totalLength)
        val accepted = try {
            sink.send(boundedPacket)
        } catch (_: Exception) {
            false
        }
        return if (accepted) PacketGateResult.FORWARDED else PacketGateResult.SINK_REJECTED
    }

    @Synchronized
    fun revoke() {
        revoked = true
        quota.revoke()
    }
}
