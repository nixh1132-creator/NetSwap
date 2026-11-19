package com.netswap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerStreamFrameTest {
    @Test fun roundTripsTcpOpenAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val original = PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 7, payload)
        val decoded = PeerStreamFrame.decode(original.encode())
        assertEquals(original.type, decoded.type)
        assertEquals(original.streamId, decoded.streamId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test fun roundTripsControlFrameOnStreamZero() {
        val original = PeerStreamFrame(PeerStreamFrame.Type.PING, 0, byteArrayOf(9))
        val decoded = PeerStreamFrame.decode(original.encode())
        assertEquals(PeerStreamFrame.Type.PING, decoded.type)
        assertEquals(0, decoded.streamId)
        assertArrayEquals(byteArrayOf(9), decoded.payload)
    }

    @Test fun rejectsTruncatedAndOversizedFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame.decode(ByteArray(13))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame.decode(ByteArray(PeerStreamFrame.MAX_FRAME_BYTES + 1))
        }
    }

    @Test fun rejectsUnknownTypeAndWrongSignature() {
        val valid = PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 1).encode()
        valid[4] = 99
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame.decode(valid)
        }
        val badMagic = PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 1).encode()
        badMagic[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame.decode(badMagic)
        }
    }

    @Test fun rejectsInvalidStreamIdsAndPayloadLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame(PeerStreamFrame.Type.DATA, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame(PeerStreamFrame.Type.PING, 2)
        }
        val malformed = PeerStreamFrame(PeerStreamFrame.Type.DATA, 1).encode()
        malformed[13] = 1
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame.decode(malformed)
        }
    }

    @Test fun rejectsPayloadAboveFrameLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerStreamFrame(
                PeerStreamFrame.Type.DATA,
                1,
                ByteArray(PeerStreamFrame.MAX_PAYLOAD_BYTES + 1)
            )
        }
    }
}
