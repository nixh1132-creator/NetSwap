package com.netswap.app

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * RFC 5389 STUN Binding message codec.
 *
 * This only discovers the address observed by a STUN server. It does not
 * establish a peer connection or guarantee traversal through carrier NAT.
 */
object StunBindingCodec {
    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val XOR_MAPPED_ADDRESS = 0x0020
    private const val MAPPED_ADDRESS = 0x0001
    private const val HEADER_BYTES = 20
    private val random = SecureRandom()

    data class Request(val bytes: ByteArray, val transactionId: ByteArray)

    fun newRequest(): Request {
        val transactionId = ByteArray(12).also(random::nextBytes)
        val packet = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        packet.putShort(BINDING_REQUEST.toShort())
        packet.putShort(0)
        packet.putInt(MAGIC_COOKIE)
        packet.put(transactionId)
        return Request(packet.array(), transactionId)
    }

    /** Parse a success response matching the request; reject malformed or unrelated packets. */
    fun parseResponse(packet: ByteArray, expectedTransactionId: ByteArray): InetSocketAddress {
        require(expectedTransactionId.size == 12) { "Invalid STUN transaction ID" }
        require(packet.size >= HEADER_BYTES) { "STUN response is too short" }
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val messageType = input.short.toInt() and 0xffff
        val messageLength = input.short.toInt() and 0xffff
        val cookie = input.int
        val transactionId = ByteArray(12).also(input::get)
        require(messageType == BINDING_SUCCESS) { "STUN response is not a Binding success" }
        require(cookie == MAGIC_COOKIE) { "Invalid STUN magic cookie" }
        require(transactionId.contentEquals(expectedTransactionId)) { "STUN transaction ID mismatch" }
        require(messageLength % 4 == 0 && packet.size == HEADER_BYTES + messageLength) {
            "Invalid STUN message length"
        }

        var fallback: InetSocketAddress? = null
        while (input.hasRemaining()) {
            require(input.remaining() >= 4) { "Truncated STUN attribute header" }
            val type = input.short.toInt() and 0xffff
            val length = input.short.toInt() and 0xffff
            require(length <= input.remaining()) { "Truncated STUN attribute value" }
            val value = ByteArray(length).also(input::get)
            val padding = (4 - (length % 4)) % 4
            require(padding <= input.remaining()) { "Truncated STUN attribute padding" }
            input.position(input.position() + padding)

            if (type == XOR_MAPPED_ADDRESS || type == MAPPED_ADDRESS) {
                val address = parseAddressAttribute(type, value, transactionId)
                if (type == XOR_MAPPED_ADDRESS) return address
                fallback = address
            }
        }
        return fallback ?: throw IllegalArgumentException("STUN response has no mapped address")
    }

    private fun parseAddressAttribute(type: Int, value: ByteArray, transactionId: ByteArray): InetSocketAddress {
        require(value.size >= 4) { "STUN mapped address is too short" }
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get().toInt() == 0) { "Invalid STUN address reserved byte" }
        val family = buffer.get().toInt() and 0xff
        val encodedPort = buffer.short.toInt() and 0xffff
        val port = if (type == XOR_MAPPED_ADDRESS) encodedPort xor (MAGIC_COOKIE ushr 16) else encodedPort
        val addressBytes = when (family) {
            0x01 -> {
                require(value.size == 8) { "Invalid IPv4 mapped address length" }
                ByteArray(4).also(buffer::get).also { bytes ->
                    if (type == XOR_MAPPED_ADDRESS) {
                        val mask = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC_COOKIE).array()
                        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i].toInt()).toByte()
                    }
                }
            }
            0x02 -> {
                require(value.size == 20) { "Invalid IPv6 mapped address length" }
                ByteArray(16).also(buffer::get).also { bytes ->
                    if (type == XOR_MAPPED_ADDRESS) {
                        val mask = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                            .putInt(MAGIC_COOKIE).put(transactionId).array()
                        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i].toInt()).toByte()
                    }
                }
            }
            else -> throw IllegalArgumentException("Unsupported STUN address family")
        }
        return InetSocketAddress(InetAddress.getByAddress(addressBytes), port).also {
            require(port in 1..65535) { "Invalid STUN mapped port" }
        }
    }
}
