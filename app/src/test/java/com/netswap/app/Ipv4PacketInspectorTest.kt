package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class Ipv4PacketInspectorTest {
    private fun packet(protocol: Int = 17, payload: ByteArray = byteArrayOf(0x04, 0xd2.toByte(), 0x00, 0x35), fragment: Int = 0): ByteArray {
        val bytes = ByteArray(20 + payload.size)
        bytes[0] = 0x45
        bytes[2] = ((bytes.size ushr 8) and 0xff).toByte()
        bytes[3] = (bytes.size and 0xff).toByte()
        bytes[6] = ((fragment ushr 8) and 0xff).toByte()
        bytes[7] = (fragment and 0xff).toByte()
        bytes[9] = protocol.toByte()
        bytes[12] = 10; bytes[13] = 0; bytes[14] = 0; bytes[15] = 2
        bytes[16] = 10; bytes[17] = 0; bytes[18] = 0; bytes[19] = 1
        payload.copyInto(bytes, 20)
        return bytes
    }

    @Test fun parsesUdpPortsAndAddresses() {
        val info = Ipv4PacketInspector.inspect(packet())
        assertEquals("10.0.0.2", info.sourceAddress)
        assertEquals("10.0.0.1", info.destinationAddress)
        assertEquals(TransportProtocol.UDP, info.protocol)
        assertEquals(1234, info.sourcePort)
        assertEquals(53, info.destinationPort)
        assertFalse(info.isFragmented)
    }

    @Test fun parsesTcpProtocol() {
        assertEquals(TransportProtocol.TCP, Ipv4PacketInspector.inspect(packet(protocol = 6)).protocol)
    }

    @Test fun nonInitialFragmentDoesNotPretendToHavePorts() {
        val info = Ipv4PacketInspector.inspect(packet(fragment = 1))
        assertTrue(info.isFragmented)
        assertNull(info.sourcePort)
        assertNull(info.destinationPort)
    }

    @Test fun rejectsTruncatedPacket() {
        val malformed = packet().copyOf(19)
        assertThrows(IllegalArgumentException::class.java) { Ipv4PacketInspector.inspect(malformed) }
    }

    @Test fun rejectsInvalidTotalLength() {
        val malformed = packet()
        malformed[2] = 0
        malformed[3] = 10
        assertThrows(IllegalArgumentException::class.java) { Ipv4PacketInspector.inspect(malformed) }
    }

    @Test fun rejectsIpv6InsteadOfMisreadingIt() {
        val malformed = packet()
        malformed[0] = 0x60
        assertThrows(IllegalArgumentException::class.java) { Ipv4PacketInspector.inspect(malformed) }
    }
}
