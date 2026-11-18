package com.netswap.app

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class Socks5UdpPacketTest {
    @Test
    fun roundTripsIpv4Datagram() {
        val target = InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53)
        val payload = byteArrayOf(1, 2, 3, 4)
        val encoded = Socks5UdpPacket.encode(target, payload)
        assertNotNull(encoded)
        val decoded = Socks5UdpPacket.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(target, decoded!!.destination)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun roundTripsIpv6Datagram() {
        val target = InetSocketAddress(InetAddress.getByName("2001:4860:4860::8888"), 443)
        val encoded = Socks5UdpPacket.encode(target, byteArrayOf(9))
        assertNotNull(encoded)
        val decoded = Socks5UdpPacket.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(target, decoded!!.destination)
        assertArrayEquals(byteArrayOf(9), decoded.payload)
    }

    @Test
    fun rejectsFragmentedAndDomainDatagrams() {
        val fragmented = byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 53)
        val domain = byteArrayOf(0, 0, 0, 3, 3) + "dns".toByteArray() + byteArrayOf(0, 53)
        assertNull(Socks5UdpPacket.decode(fragmented))
        assertNull(Socks5UdpPacket.decode(domain))
    }

    @Test
    fun rejectsMalformedAndUnsafeDestinations() {
        assertNull(Socks5UdpPacket.decode(byteArrayOf(0, 0, 0)))
        assertNull(Socks5UdpPacket.decode(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 53)))
        assertNull(Socks5UdpPacket.encode(InetSocketAddress("0.0.0.0", 53), byteArrayOf(1)))
    }
}
