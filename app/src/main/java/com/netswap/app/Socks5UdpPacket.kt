package com.netswap.app

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Strict RFC 1928 UDP request/reply codec.
 *
 * Only complete, unfragmented datagrams with numeric IPv4/IPv6 destinations
 * are accepted. Domain names are intentionally rejected to avoid DNS leakage.
 * This codec does not open sockets or forward traffic.
 */
data class Socks5UdpDatagram(
    val destination: InetSocketAddress,
    val payload: ByteArray
)

object Socks5UdpPacket {
    fun decode(packet: ByteArray): Socks5UdpDatagram? {
        if (packet.size < 4) return null
        if (packet[0].toInt() != 0 || packet[1].toInt() != 0) return null
        if (packet[2].toInt() != 0) return null // fragmentation is not supported

        val addressType = packet[3].toInt() and 0xff
        val addressLength = when (addressType) {
            1 -> 4
            4 -> 16
            3 -> return null // no domain parsing / DNS
            else -> return null
        }
        val portOffset = 4 + addressLength
        if (packet.size < portOffset + 2) return null

        val addressBytes = packet.copyOfRange(4, portOffset)
        val address = try { InetAddress.getByAddress(addressBytes) } catch (_: Exception) { return null }
        val port = ((packet[portOffset].toInt() and 0xff) shl 8) or
            (packet[portOffset + 1].toInt() and 0xff)
        if (port == 0 || address.isAnyLocalAddress || address.isMulticastAddress) return null

        return Socks5UdpDatagram(
            InetSocketAddress(address, port),
            packet.copyOfRange(portOffset + 2, packet.size)
        )
    }

    fun encode(destination: InetSocketAddress, payload: ByteArray): ByteArray? {
        if (destination.isUnresolved || destination.port !in 1..65535) return null
        val address = destination.address ?: return null
        if (address.isAnyLocalAddress || address.isMulticastAddress) return null
        val raw = address.address
        val addressType = when (raw.size) {
            4 -> 1
            16 -> 4
            else -> return null
        }
        return byteArrayOf(0, 0, 0, addressType.toByte()) + raw +
            byteArrayOf((destination.port ushr 8).toByte(), destination.port.toByte()) + payload
    }
}
