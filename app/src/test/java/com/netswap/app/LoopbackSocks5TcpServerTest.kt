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

    @Test
    fun udpAssociateRelaysLoopbackEchoAndStopsWithControlSocket() {
        val echo = java.net.DatagramSocket(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val echoThread = Thread {
            try {
                val bytes = ByteArray(512)
                val packet = java.net.DatagramPacket(bytes, bytes.size)
                echo.receive(packet)
                val echoed = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                echo.send(java.net.DatagramPacket(echoed, echoed.size, packet.socketAddress))
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val server = LoopbackSocks5TcpServer(
            TcpUpstreamOpener { _, _ -> error("TCP path not used") },
            "user", "pass",
            udpUpstream = UdpUpstreamOpener { java.net.DatagramSocket() }
        )
        try {
            val port = server.start()
            Socket("127.0.0.1", port).use { control ->
                control.soTimeout = 3_000
                val input = control.getInputStream()
                val output = control.getOutputStream()
                output.write(byteArrayOf(5, 1, 2)); output.flush()
                assertEquals(5, input.read()); assertEquals(2, input.read())
                output.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray())
                output.flush()
                assertEquals(1, input.read()); assertEquals(0, input.read())
                output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
                val reply = ByteArray(10)
                var offset = 0
                while (offset < reply.size) {
                    val count = input.read(reply, offset, reply.size - offset)
                    check(count > 0); offset += count
                }
                assertEquals(5, reply[0].toInt()); assertEquals(0, reply[1].toInt())
                assertEquals(127, reply[4].toInt() and 0xff)
                val relayPort = ((reply[8].toInt() and 0xff) shl 8) or (reply[9].toInt() and 0xff)
                java.net.DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { udpClient ->
                    udpClient.soTimeout = 5_000
                    val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), echo.localPort)
                    val encoded = Socks5UdpPacket.encode(target, "netswap-udp".toByteArray())!!
                    udpClient.send(java.net.DatagramPacket(encoded, encoded.size,
                        InetSocketAddress(InetAddress.getByName("127.0.0.1"), relayPort)))
                    val response = java.net.DatagramPacket(ByteArray(512), 512)
                    udpClient.receive(response)
                    val decoded = Socks5UdpPacket.decode(response.data.copyOf(response.length))
                    assertEquals(target, decoded!!.destination)
                    assertEquals("netswap-udp", String(decoded.payload))
                }
            }
        } finally {
            server.close()
            echo.close()
            echoThread.join(1_000)
        }
    }

}
