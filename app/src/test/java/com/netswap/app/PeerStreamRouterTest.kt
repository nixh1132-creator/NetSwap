package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class PeerStreamRouterTest {
    @Test fun opensRoutesAndClosesTcpStream() {
        val opened = mutableListOf<Int>()
        val received = mutableListOf<Byte>()
        val closed = mutableListOf<Int>()
        val router = PeerStreamRouter(
            onOpen = { id, protocol, destination ->
                assertEquals(PeerStreamRouter.Protocol.TCP, protocol)
                assertArrayEquals(byteArrayOf(1, 2), destination)
                opened.add(id)
                true
            },
            onData = { id, _, payload -> if (id == 4) received.addAll(payload.toList()) },
            onClose = { closed.add(it) },
            onControl = {}
        )

        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 4, byteArrayOf(1, 2))))
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.DATA, 4, byteArrayOf(8, 9))))
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 4)))
        assertEquals(listOf(4), opened)
        assertEquals(listOf<Byte>(8, 9), received)
        assertEquals(listOf(4), closed)
        assertEquals(0, router.activeStreamCount())
    }

    @Test fun rejectsDataForUnknownOrWrongProtocolStream() {
        val router = PeerStreamRouter({ _, _, _ -> true }, { _, _, _ -> }, {}, {})
        assertFalse(router.accept(PeerStreamFrame(PeerStreamFrame.Type.DATA, 1, byteArrayOf(1))))
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_UDP, 2, byteArrayOf(1))))
        assertFalse(router.accept(PeerStreamFrame(PeerStreamFrame.Type.DATA, 2, byteArrayOf(1))))
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.UDP_DATA, 2, byteArrayOf(1))))
    }

    @Test fun enforcesStreamLimitAndDuplicateIds() {
        val router = PeerStreamRouter({ _, _, _ -> true }, { _, _, _ -> }, {}, {}, maxStreams = 1)
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 1, byteArrayOf(1))))
        assertFalse(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 1, byteArrayOf(1))))
        assertFalse(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 2, byteArrayOf(1))))
        assertEquals(1, router.activeStreamCount())
    }

    @Test fun closeAllIsIdempotentAndRejectsFurtherFrames() {
        val closed = mutableListOf<Int>()
        val router = PeerStreamRouter({ _, _, _ -> true }, { _, _, _ -> }, { closed.add(it) }, {})
        assertTrue(router.accept(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 7, byteArrayOf(1))))
        router.closeAll()
        router.closeAll()
        assertEquals(listOf(7), closed)
        assertFalse(router.accept(PeerStreamFrame(PeerStreamFrame.Type.PING, 0)))
        assertEquals(0, router.activeStreamCount())
    }

    @Test fun rejectedOpenDoesNotReserveStreamId() {
        var allow = false
        val router = PeerStreamRouter({ _, _, _ -> allow }, { _, _, _ -> }, {}, {})
        val open = PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 3, byteArrayOf(1))
        assertFalse(router.accept(open))
        allow = true
        assertTrue(router.accept(open))
    }
}
