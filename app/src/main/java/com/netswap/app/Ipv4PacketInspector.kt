package com.netswap.app

/**
 * Minimal, allocation-light IPv4 header inspector for the future sender data plane.
 *
 * This class does not forward packets, open sockets, or inspect application payloads.
 * It validates packet boundaries before a future, separately reviewed forwarding engine
 * is allowed to process them.
 */
data class Ipv4PacketInfo(
    val sourceAddress: String,
    val destinationAddress: String,
    val protocol: TransportProtocol,
    val totalLength: Int,
    val headerLength: Int,
    val isFragmented: Boolean,
    val sourcePort: Int?,
    val destinationPort: Int?
)

enum class TransportProtocol { TCP, UDP, ICMP, OTHER }

object Ipv4PacketInspector {
    fun inspect(packet: ByteArray): Ipv4PacketInfo {
        require(packet.size >= 20) { "IPv4 packet is shorter than its minimum header." }
        val version = (packet[0].toInt() ushr 4) and 0x0f
        require(version == 4) { "Only IPv4 packets are supported by this inspector." }
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        require(headerLength >= 20 && headerLength <= packet.size) { "Invalid IPv4 header length." }
        val totalLength = u16(packet, 2)
        require(totalLength >= headerLength && totalLength <= packet.size) { "Invalid IPv4 total length." }

        val fragmentField = u16(packet, 6)
        val fragmentOffset = fragmentField and 0x1fff
        val moreFragments = (fragmentField and 0x2000) != 0
        val firstFragment = fragmentOffset == 0
        val protocolNumber = packet[9].toInt() and 0xff
        val protocol = when (protocolNumber) {
            6 -> TransportProtocol.TCP
            17 -> TransportProtocol.UDP
            1 -> TransportProtocol.ICMP
            else -> TransportProtocol.OTHER
        }

        var sourcePort: Int? = null
        var destinationPort: Int? = null
        if (firstFragment && (protocol == TransportProtocol.TCP || protocol == TransportProtocol.UDP)) {
            require(totalLength >= headerLength + 4) { "Transport header is truncated." }
            sourcePort = u16(packet, headerLength)
            destinationPort = u16(packet, headerLength + 2)
        }

        return Ipv4PacketInfo(
            sourceAddress = address(packet, 12),
            destinationAddress = address(packet, 16),
            protocol = protocol,
            totalLength = totalLength,
            headerLength = headerLength,
            isFragmented = moreFragments || fragmentOffset != 0,
            sourcePort = sourcePort,
            destinationPort = destinationPort
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun address(bytes: ByteArray, offset: Int): String =
        (0..3).joinToString(".") { (bytes[offset + it].toInt() and 0xff).toString() }
}
