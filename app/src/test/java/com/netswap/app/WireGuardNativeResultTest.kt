package com.netswap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardNativeResultTest {
    @Test fun networkOutputIsDistinctFromDecryptedTunnelPacket() {
        val bytes = byteArrayOf(1, 2, 3)
        val network = decodeWireGuardNativeResult(byteArrayOf(1) + bytes)
        val tunnel = decodeWireGuardNativeResult(byteArrayOf(2) + bytes)

        assertTrue(network is WireGuardEngineResult.NetworkDatagram)
        assertTrue(tunnel is WireGuardEngineResult.TunnelPacket)
        assertEquals(4, (tunnel as WireGuardEngineResult.TunnelPacket).ipVersion)
    }

    @Test fun ipv6TunnelPacketIsMarkedAsIpv6() {
        val result = decodeWireGuardNativeResult(byteArrayOf(3, 0x60, 0, 0))
        assertEquals(6, (result as WireGuardEngineResult.TunnelPacket).ipVersion)
    }

    @Test fun emptyAndUnknownOutputsFailClosed() {
        assertTrue(decodeWireGuardNativeResult(byteArrayOf(0, 1)) is WireGuardEngineResult.Failure)
        assertTrue(decodeWireGuardNativeResult(byteArrayOf(1)) is WireGuardEngineResult.Failure)
        assertTrue(decodeWireGuardNativeResult(byteArrayOf(99)) is WireGuardEngineResult.Failure)
        assertTrue(decodeWireGuardNativeResult(byteArrayOf()) is WireGuardEngineResult.Failure)
    }

    @Test fun doneOutputIsNoPacket() {
        assertTrue(decodeWireGuardNativeResult(byteArrayOf(0)) is WireGuardEngineResult.NoPacket)
    }
}
