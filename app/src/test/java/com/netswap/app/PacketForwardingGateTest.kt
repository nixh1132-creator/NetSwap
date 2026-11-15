package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class PacketForwardingGateTest {
    private fun udpPacket(extraBuffer: Int = 0, fragment: Int = 0): ByteArray {
        val length = 24
        val bytes = ByteArray(length + extraBuffer)
        bytes[0] = 0x45
        bytes[2] = 0
        bytes[3] = length.toByte()
        bytes[6] = ((fragment ushr 8) and 0xff).toByte()
        bytes[7] = (fragment and 0xff).toByte()
        bytes[9] = 17
        bytes[12] = 10; bytes[15] = 2
        bytes[16] = 10; bytes[19] = 1
        bytes[20] = 0; bytes[21] = 53
        bytes[22] = 0; bytes[23] = 53
        return bytes
    }

    @Test fun validatesAndForwardsOnlyDeclaredIpv4Length() {
        var forwarded: ByteArray? = null
        val gate = PacketForwardingGate(SessionTrafficQuota(100), TrafficDirection.SENT_TO_RECEIVER) {
            forwarded = it
            true
        }
        assertEquals(PacketGateResult.FORWARDED, gate.process(udpPacket(extraBuffer = 12)))
        assertEquals(24, forwarded?.size)
    }

    @Test fun malformedPacketNeverReachesSink() {
        var calls = 0
        val gate = PacketForwardingGate(SessionTrafficQuota(100), TrafficDirection.SENT_TO_RECEIVER) {
            calls++
            true
        }
        assertEquals(PacketGateResult.INVALID_PACKET, gate.process(byteArrayOf(1, 2, 3)))
        assertEquals(0, calls)
    }

    @Test fun fragmentsAreRejectedBeforeQuotaOrSink() {
        var calls = 0
        val quota = SessionTrafficQuota(100)
        val gate = PacketForwardingGate(quota, TrafficDirection.SENT_TO_RECEIVER) {
            calls++
            true
        }
        assertEquals(PacketGateResult.UNSUPPORTED_FRAGMENT, gate.process(udpPacket(fragment = 0x2000)))
        assertEquals(0, calls)
        assertEquals(0L, quota.snapshot().totalBytes)
    }

    @Test fun quotaIsReservedBeforeSinkAndStopsOverLimitPacket() {
        var calls = 0
        val quota = SessionTrafficQuota(30)
        val gate = PacketForwardingGate(quota, TrafficDirection.SENT_TO_RECEIVER) {
            calls++
            true
        }
        assertEquals(PacketGateResult.FORWARDED, gate.process(udpPacket()))
        assertEquals(PacketGateResult.QUOTA_EXHAUSTED, gate.process(udpPacket()))
        assertEquals(1, calls)
        assertEquals(24L, quota.snapshot().totalBytes)
    }

    @Test fun sinkFailureIsChargedConservatively() {
        val quota = SessionTrafficQuota(100)
        val gate = PacketForwardingGate(quota, TrafficDirection.RECEIVED_FROM_RECEIVER) { false }
        assertEquals(PacketGateResult.SINK_REJECTED, gate.process(udpPacket()))
        assertEquals(24L, quota.snapshot().totalBytes)
    }

    @Test fun revokeStopsSubsequentPackets() {
        var calls = 0
        val gate = PacketForwardingGate(SessionTrafficQuota(100), TrafficDirection.SENT_TO_RECEIVER) {
            calls++
            true
        }
        gate.revoke()
        assertEquals(PacketGateResult.SESSION_REVOKED, gate.process(udpPacket()))
        assertEquals(0, calls)
    }
}
