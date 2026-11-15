package com.netswap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WireGuardDatagramPumpTest {
    private class FakeEngine : UserspaceWireGuardEngine {
        val closed = AtomicBoolean(false)
        override fun encrypt(innerPacket: ByteArray) =
            WireGuardEngineResult.NetworkDatagram(byteArrayOf(11) + innerPacket)
        override fun decrypt(wireGuardDatagram: ByteArray) =
            if (wireGuardDatagram.contentEquals(byteArrayOf(22, 33)))
                WireGuardEngineResult.TunnelPacket(byteArrayOf(0x45, 1), 4)
            else WireGuardEngineResult.NoPacket
        override fun tick() = WireGuardEngineResult.NoPacket
        override fun close() { closed.set(true) }
    }

    @Test fun sendsEncryptedDatagramAndDeliversDecryptedTunnelPacket() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val engine = FakeEngine()
            val delivered = CountDownLatch(1)
            val pump = WireGuardDatagramPump(
                engine,
                ProtectedUdpTransport(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                    protectSocket = { true },
                    receiveTimeoutMs = 100
                ),
                onTunnelPacket = { packet, version ->
                    assertEquals(4, version)
                    assertEquals(0x45, packet[0].toInt() and 0xff)
                    delivered.countDown()
                }
            )
            try {
                pump.start()
                pump.sendTunnelPacket(byteArrayOf(1, 2, 3))
                val outbound = DatagramPacket(ByteArray(32), 32)
                server.receive(outbound)
                assertTrue(outbound.data.copyOf(outbound.length).contentEquals(byteArrayOf(11, 1, 2, 3)))
                val reply = byteArrayOf(22, 33)
                server.send(DatagramPacket(reply, reply.size, outbound.socketAddress))
                assertTrue(delivered.await(2, TimeUnit.SECONDS))
            } finally {
                pump.close()
            }
            assertTrue(engine.closed.get())
        }
    }

    @Test fun closeBeforeStartReleasesEngineAndTransport() {
        val engine = FakeEngine()
        val transport = ProtectedUdpTransport(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 9),
            protectSocket = { true }
        )
        WireGuardDatagramPump(engine, transport, onTunnelPacket = { _, _ -> }).close()
        assertTrue(engine.closed.get())
        try {
            transport.receive()
            throw AssertionError("Expected transport to be closed")
        } catch (_: IllegalStateException) { }
    }
}
