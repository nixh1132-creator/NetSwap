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

        assertTrue(bridge.openTcp(9, byteArrayOf(8, 0, 1)))
        assertEquals(PeerStreamFrame.Type.OPEN_TCP, PeerStreamFrame.decode(sent.last()).type)
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, 9, byteArrayOf(8, 0, 1)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.DATA, 9, byteArrayOf(4, 5)).encode()))
        assertTrue(bridge.receive(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, 9).encode()))
        assertEquals(listOf(9), opened)
        assertEquals(listOf<Byte>(4, 5), received)
        assertEquals(listOf(9), closed)
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
