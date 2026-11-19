package com.netswap.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded binary framing for multiplexing proxy streams over one WebRTC DataChannel.
 *
 * The codec is consumed by PeerStreamTransportBridge and WebRtcSessionController
 * to multiplex receiver-originated TCP and UDP proxy streams over the peer channel.
 */
data class PeerStreamFrame(
    val type: Type,
    val streamId: Int,
    val payload: ByteArray = ByteArray(0)
) {
    enum class Type(val wire: Int) {
        OPEN_TCP(1),
        OPEN_UDP(2),
        DATA(3),
        UDP_DATA(4),
        CLOSE(5),
        ERROR(6),
        PING(7),
        PONG(8);

        companion object {
            fun fromWire(value: Int): Type =
                entries.firstOrNull { it.wire == value }
                    ?: throw IllegalArgumentException("Unknown NetSwap frame type.")
        }
    }

    init {
        require(streamId >= 0) { "Stream ID cannot be negative." }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Frame payload is too large." }
        require(type == Type.PING || type == Type.PONG || streamId != 0) {
            "Stream frames require a non-zero stream ID."
        }
        require(type != Type.PING && type != Type.PONG || streamId == 0) {
            "Control frames must use stream ID zero."
        }
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put(type.wire.toByte())
        buffer.putInt(streamId)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        private const val MAGIC = 0x4E535750 // "NSWP"
        private const val VERSION = 1
        private const val HEADER_BYTES = 14
        const val MAX_FRAME_BYTES = 16 * 1024
        const val MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES

        fun decode(bytes: ByteArray): PeerStreamFrame {
            require(bytes.size in HEADER_BYTES..MAX_FRAME_BYTES) { "Invalid NetSwap frame size." }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(buffer.int == MAGIC) { "Invalid NetSwap frame signature." }
            require((buffer.get().toInt() and 0xff) == VERSION) { "Unsupported NetSwap frame version." }
            val type = Type.fromWire(buffer.get().toInt() and 0xff)
            val streamId = buffer.int
            val payloadLength = buffer.int
            require(payloadLength >= 0 && payloadLength == buffer.remaining()) {
                "NetSwap frame length does not match payload."
            }
            val payload = ByteArray(payloadLength)
            buffer.get(payload)
            return PeerStreamFrame(type, streamId, payload)
        }
    }
}
