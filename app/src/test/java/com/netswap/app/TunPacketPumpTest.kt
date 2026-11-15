package com.netswap.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TunPacketPumpTest {
    private class FakeDevice : TunPacketDevice {
        val incoming = LinkedBlockingQueue<ByteArray?>()
        val written = LinkedBlockingQueue<ByteArray>()
        val closed = AtomicBoolean(false)

        override fun readPacket(buffer: ByteArray): Int {
            val packet = incoming.take() ?: return -1
            require(packet.size <= buffer.size)
            packet.copyInto(buffer)
            return packet.size
        }

        override fun writePacket(packet: ByteArray) {
            check(!closed.get())
            written.put(packet)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) incoming.offer(null)
        }
    }

    @Test fun readsCompletePacketsAsIndependentCopies() {
        val device = FakeDevice()
        val received = LinkedBlockingQueue<ByteArray>()
        val pump = TunPacketPump(device, onPacket = { received.offer(it) })
        try {
            pump.start()
            val first = byteArrayOf(0x45, 1, 2)
            device.incoming.put(first)
            val firstReceived = received.poll(2, TimeUnit.SECONDS)
            first[1] = 99
            assertArrayEquals(byteArrayOf(0x45, 1, 2), firstReceived)
            device.incoming.put(byteArrayOf(0x46, 3))
            assertArrayEquals(byteArrayOf(0x46, 3), received.poll(2, TimeUnit.SECONDS))
        } finally {
            pump.close()
        }
        assertTrue(device.closed.get())
    }

    @Test fun writesBoundedPacketAndClosesIdempotently() {
        val device = FakeDevice()
        val pump = TunPacketPump(device, onPacket = {})
        pump.start()
        val packet = byteArrayOf(0x45, 7, 8)
        pump.writePacket(packet)
        packet[1] = 0
        assertArrayEquals(byteArrayOf(0x45, 7, 8), device.written.poll(2, TimeUnit.SECONDS))
        pump.close()
        pump.close()
        assertTrue(device.closed.get())
    }

    @Test fun rejectsOversizedWrites() {
        val device = FakeDevice()
        val pump = TunPacketPump(device, onPacket = {})
        pump.start()
        try {
            pump.writePacket(ByteArray(TunPacketPump.MAX_PACKET_SIZE + 1))
            throw AssertionError("Expected oversized packet rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(false, device.closed.get())
        } finally {
            pump.close()
        }
    }
}
