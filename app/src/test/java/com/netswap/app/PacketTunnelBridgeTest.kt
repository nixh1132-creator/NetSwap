package com.netswap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PacketTunnelBridgeTest {
    private class FakeTunDevice : TunPacketDevice {
        val incoming = LinkedBlockingQueue<ByteArray?>()
        val written = LinkedBlockingQueue<ByteArray>()
        val closed = AtomicBoolean(false)

        override fun readPacket(buffer: ByteArray): Int {
            val packet = incoming.take() ?: return -1
            packet.copyInto(buffer)
            return packet.size
        }

        override fun writePacket(packet: ByteArray) {
            check(!closed.get())
            written.put(packet.copyOf())
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) incoming.offer(null)
        }
    }

    private class FakeEngine : UserspaceWireGuardEngine {
        override fun encrypt(innerPacket: ByteArray) =
            WireGuardEngineResult.NetworkDatagram(byteArrayOf(0x55) + innerPacket)

        override fun decrypt(wireGuardDatagram: ByteArray): WireGuardEngineResult =
            if (wireGuardDatagram.isNotEmpty() && wireGuardDatagram[0] == 0x66.toByte()) {
                WireGuardEngineResult.TunnelPacket(wireGuardDatagram.copyOfRange(1, wireGuardDatagram.size), 4)
            } else {
                WireGuardEngineResult.NoPacket
            }

        override fun tick() = WireGuardEngineResult.NoPacket
        override fun close() = Unit
    }

    @Test fun forwardsBothDirectionsThroughWireGuardAndSharedQuota() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { gateway ->
            gateway.soTimeout = 2_000
            val tun = FakeTunDevice()
            val events = LinkedBlockingQueue<Pair<PacketGateResult, TrafficDirection>>()
            val bridge = PacketTunnelBridge(
                tunDevice = tun,
                engine = FakeEngine(),
                endpoint = InetSocketAddress(InetAddress.getLoopbackAddress(), gateway.localPort),
                protectSocket = { true },
                quotaBytes = 40,
                onGateResult = { result, direction -> events.offer(result to direction) }
            )
            try {
                bridge.start()
                val outboundIp = ipv4Packet()
                tun.incoming.put(outboundIp)

                val encrypted = DatagramPacket(ByteArray(128), 128)
                gateway.receive(encrypted)
                assertArrayEquals(byteArrayOf(0x55) + outboundIp, encrypted.data.copyOf(encrypted.length))
                assertEquals(
                    PacketGateResult.FORWARDED to TrafficDirection.SENT_TO_RECEIVER,
                    events.poll(2, TimeUnit.SECONDS)
                )

                val inboundIp = ipv4Packet(destination = 9)
                val response = byteArrayOf(0x66) + inboundIp
                gateway.send(DatagramPacket(response, response.size, encrypted.socketAddress))
                assertArrayEquals(inboundIp, tun.written.poll(2, TimeUnit.SECONDS))
                assertEquals(
                    PacketGateResult.FORWARDED to TrafficDirection.RECEIVED_FROM_RECEIVER,
                    events.poll(2, TimeUnit.SECONDS)
                )

                val snapshot = bridge.snapshot()
                assertEquals(40L, snapshot.totalBytes)
                assertEquals(0L, snapshot.remainingBytes)
                assertEquals(20L, snapshot.sentToReceiverBytes)
                assertEquals(20L, snapshot.receivedFromReceiverBytes)
                assertTrue(!snapshot.active)
            } finally {
                bridge.close()
            }
            assertTrue(tun.closed.get())
        }
    }

    @Test fun revokeStopsFurtherTunPackets() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { gateway ->
            val tun = FakeTunDevice()
            val events = LinkedBlockingQueue<PacketGateResult>()
            val bridge = PacketTunnelBridge(
                tunDevice = tun,
                engine = FakeEngine(),
                endpoint = InetSocketAddress(InetAddress.getLoopbackAddress(), gateway.localPort),
                protectSocket = { true },
                quotaBytes = 1_000,
                onGateResult = { result, _ -> events.offer(result) }
            )
            bridge.start()
            bridge.revoke()
            assertTrue(tun.closed.get())
            assertEquals(false, bridge.snapshot().active)
            bridge.close()
        }
    }

    private fun ipv4Packet(destination: Int = 2): ByteArray = ByteArray(20).also {
        it[0] = 0x45
        it[2] = 0
        it[3] = 20
        it[8] = 64
        it[9] = 1
        it[12] = 10
        it[15] = 1
        it[16] = 10
        it[19] = destination.toByte()
    }
}
