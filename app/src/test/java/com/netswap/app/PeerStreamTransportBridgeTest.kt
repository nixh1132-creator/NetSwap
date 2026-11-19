package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class PeerStreamTransportBridgeTest {
    @Test fun sendsFramesAndRoutesIncomingMessages() {
        val sent = mutableListOf<ByteArray>()
        val opened = mutableListOf<Int>()
        val received = mutableListOf<Byte>()
        val closed = mutableListOf<Int>()
        val bridge = PeerStreamTransportBridge(
            sendBinary = { sent.add(it); true },
            onOpen = { id, protocol, destination ->
                assertEquals(PeerStreamRouter.Protocol.TCP, protocol)
                assertArrayEquals(byteArrayOf(8, 0, 1), destination)
                opened.add(id)
                true
            },
            onData = { id, _, payload -> if (id == 9) received.addAll(payload.toList()) },
            onClose = { closed.add(it) }
        )

        // Stream 9 is opened locally; only peer DATA/CLOSE should return on it.
        assertTrue(bridge.openTcp(9, byteArrayOf(8, 0, 1)))
        assertEquals(PeerStreamFrame.Type.OPEN_TCP, PeerStreamFrame.decode(sent.last()).type)
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.DATA, 9, byteArrayOf(4, 5)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 9).encode()))

        // Stream 10 is opened by the peer and is accepted through the remote OPEN path.
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 10, byteArrayOf(8, 0, 1)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.DATA, 10, byteArrayOf(6)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 10).encode()))
        assertEquals(listOf(10), opened)
        assertEquals(listOf<Byte>(4, 5), received)
        assertEquals(listOf(9, 10), closed)
        bridge.close()
    }

    @Test fun locallyOpenedStreamAcceptsPeerResponseDataAndClose() {
        val received = mutableListOf<Byte>()
        val closed = mutableListOf<Int>()
        val bridge = PeerStreamTransportBridge(
            sendBinary = { true },
            onOpen = { _, _, _ -> fail("A response must not reopen the local stream"); false },
            onData = { id, protocol, payload ->
                assertEquals(21, id)
                assertEquals(PeerStreamRouter.Protocol.TCP, protocol)
                received.addAll(payload.toList())
            },
            onClose = { closed.add(it) }
        )

        assertTrue(bridge.openTcp(21, byteArrayOf(1, 2, 3)))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.DATA, 21, byteArrayOf(7, 8)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 21).encode()))
        assertEquals(listOf<Byte>(7, 8), received)
        assertEquals(listOf(21), closed)
        assertFalse(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.DATA, 21, byteArrayOf(9)).encode()))
        bridge.close()
    }

    @Test fun failedLocalOpenReleasesStreamId() {
        var allowSend = false
        val bridge = PeerStreamTransportBridge(
            sendBinary = { allowSend },
            onOpen = { _, _, _ -> true },
            onData = { _, _, _ -> },
            onClose = {}
        )
        assertFalse(bridge.openTcp(22, byteArrayOf(1)))
        allowSend = true
        assertTrue(bridge.openTcp(22, byteArrayOf(1)))
        bridge.close()
    }

    @Test fun repliesToPingAndRejectsMalformedFrames() {
        val sent = mutableListOf<ByteArray>()
        val bridge = PeerStreamTransportBridge({ sent.add(it); true }, { _, _, _ -> true }, { _, _, _ -> }, {})
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.PING, 0).encode()))
        assertEquals(PeerStreamFrame.Type.PONG, PeerStreamFrame.decode(sent.single()).type)
        assertFalse(bridge.receive(byteArrayOf(1, 2, 3)))
        bridge.close()
        assertFalse(bridge.sendPing())
    }
}
