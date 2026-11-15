package com.netswap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread

class ProtectedUdpTransportTest {
    @Test fun protectsSocketBeforeSendingAndReceivesPeerDatagram() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val payload = byteArrayOf(1, 2, 3, 4)
            var protected = false
            ProtectedUdpTransport(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                protectSocket = { socket ->
                    assertTrue(socket.isBound)
                    assertFalse(socket.isConnected)
                    protected = true
                    true
                },
                receiveTimeoutMs = 2_000
            ).use { transport ->
                assertTrue(protected)
                transport.send(payload)
                val received = DatagramPacket(ByteArray(16), 16)
                server.receive(received)
                assertArrayEquals(payload, received.data.copyOf(received.length))
                server.send(DatagramPacket(payload, payload.size, received.socketAddress))
                assertArrayEquals(payload, transport.receive())
            }
        }
    }

    @Test fun failedProtectionClosesSocketAndRejectsTransport() {
        var socketRef: DatagramSocket? = null
        try {
            ProtectedUdpTransport(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 9),
                protectSocket = { socketRef = it; false }
            )
            throw AssertionError("Expected protection failure")
        } catch (expected: IllegalStateException) {
            assertTrue(socketRef?.isClosed == true)
        }
    }

    @Test fun receiveReturnsNullOnTimeout() {
        DatagramSocket(0).use { server ->
            ProtectedUdpTransport(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                protectSocket = { true },
                receiveTimeoutMs = 50
            ).use { transport ->
                assertNull(transport.receive())
            }
        }
    }

    @Test fun closeIsIdempotentAndStopsFurtherUse() {
        val transport = ProtectedUdpTransport(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 9),
            protectSocket = { true }
        )
        transport.close()
        transport.close()
        try {
            transport.receive()
            throw AssertionError("Expected closed transport")
        } catch (expected: IllegalStateException) {
            assertEquals("UDP transport is closed", expected.message)
        }
    }
}
