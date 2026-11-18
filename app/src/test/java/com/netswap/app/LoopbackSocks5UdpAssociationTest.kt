package com.netswap.app

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackSocks5UdpAssociationTest {
    @Test
    fun bindsRelayToLoopbackAndFollowsControlConnectionLifetime() {
        val listener = ServerSocket()
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val client = Socket("127.0.0.1", listener.localPort)
        val control = listener.accept()
        listener.close()

        val association = LoopbackSocks5UdpAssociation(UdpUpstreamOpener { DatagramSocket() })
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { association.run(control, control.getOutputStream()) }
            catch (t: Throwable) { failure.set(t) }
        }
        worker.start()
        try {
            client.soTimeout = 2_000
            val reply = ByteArray(10)
            var offset = 0
            while (offset < reply.size) {
                val count = client.getInputStream().read(reply, offset, reply.size - offset)
                check(count > 0)
                offset += count
            }
            assertEquals(5, reply[0].toInt())
            assertEquals(0, reply[1].toInt())
            assertEquals(1, reply[3].toInt())
            assertEquals(127, reply[4].toInt() and 0xff)
            assertEquals(0, reply[5].toInt())
            assertEquals(0, reply[6].toInt())
            assertEquals(1, reply[7].toInt())
            val port = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)
            assertTrue(port in 1..65535)
        } finally {
            client.close()
            worker.join(2_000)
            association.close()
        }
        assertTrue(!worker.isAlive)
        assertEquals(null, failure.get())
    }
}
