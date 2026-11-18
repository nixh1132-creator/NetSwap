package com.netswap.app

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackSocks5TcpServerTest {
    @Test
    fun bindsToLoopbackAndStartsWithSingleClientLimit() {
        val server = LoopbackSocks5TcpServer(
            TcpUpstreamOpener { _, _ -> error("No upstream expected") },
            "user", "pass", maxClients = 1
        )
        try {
            val port = server.start()
            Socket().use { client ->
                client.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
                assertTrue(client.isConnected)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsWrongPassword() {
        val server = LoopbackSocks5TcpServer(
            TcpUpstreamOpener { _, _ -> error("No upstream expected") },
            "user", "pass"
        )
        try {
            val port = server.start()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertEquals(5, input.read())
                assertEquals(2, input.read())
                output.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(4) + "nope".toByteArray())
                output.flush()
                assertEquals(1, input.read())
                assertEquals(1, input.read())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsDomainDestinationsWithoutOpeningUpstream() {
        val opened = AtomicInteger()
        val server = LoopbackSocks5TcpServer(
            TcpUpstreamOpener { _, _ -> opened.incrementAndGet(); error("Domain must not resolve") },
            "user", "pass"
        )
        try {
            val port = server.start()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertEquals(5, input.read())
                assertEquals(2, input.read())
                output.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray())
                output.flush()
                assertEquals(1, input.read())
                assertEquals(0, input.read())
                output.write(byteArrayOf(5, 1, 0, 3, 3) + "abc".toByteArray() + byteArrayOf(0, 80))
                output.flush()
                assertEquals(5, input.read())
                assertEquals(8, input.read())
                assertEquals(0, opened.get())
            }
        } finally {
            server.close()
        }
    }
}
