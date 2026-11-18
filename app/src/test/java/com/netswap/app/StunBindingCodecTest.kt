package com.netswap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StunBindingCodecTest {
    @Test fun createsValidBindingRequest() {
        val request = StunBindingCodec.newRequest()
        assertEquals(20, request.bytes.size)
        val header = ByteBuffer.wrap(request.bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x0001, header.short.toInt() and 0xffff)
        assertEquals(0, header.short.toInt() and 0xffff)
        assertEquals(0x2112A442, header.int)
        val id = ByteArray(12).also(header::get)
        assertEquals(true, id.contentEquals(request.transactionId))
    }

    @Test fun parsesXorMappedIpv4Response() {
        val request = StunBindingCodec.newRequest()
        val ip = byteArrayOf(203.toByte(), 0, 113, 7)
        val port = 54321
        val cookie = 0x2112A442
        val mask = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(cookie).array()
        val value = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(1).putShort((port xor (cookie ushr 16)).toShort())
            .put(ByteArray(4) { i -> (ip[i].toInt() xor mask[i].toInt()).toByte() }).array()
        val response = response(request.transactionId, 0x0020, value)
        val mapped = StunBindingCodec.parseResponse(response, request.transactionId)
        assertEquals(InetAddress.getByAddress(ip), mapped.address)
        assertEquals(port, mapped.port)
    }

    @Test fun rejectsMismatchedTransactionId() {
        val request = StunBindingCodec.newRequest()
        val value = byteArrayOf(0, 1, 0, 1, 127, 0, 0, 1)
        val response = response(ByteArray(12), 0x0001, value)
        assertThrows(IllegalArgumentException::class.java) {
            StunBindingCodec.parseResponse(response, request.transactionId)
        }
    }

    private fun response(id: ByteArray, attributeType: Int, value: ByteArray): ByteArray {
        val paddedLength = (value.size + 3) and -4
        return ByteBuffer.allocate(20 + 4 + paddedLength).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x0101.toShort()).putShort((4 + paddedLength).toShort())
            .putInt(0x2112A442).put(id).putShort(attributeType.toShort())
            .putShort(value.size.toShort()).put(value).put(ByteArray(paddedLength - value.size)).array()
    }
}
