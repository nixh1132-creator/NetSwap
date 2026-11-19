package com.netswap.app

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReceiverSocks5ProxyTest {
    @Test fun udpAssociatePreservesDomainAndReturnsPeerDatagrams() {
        val streamId = AtomicInteger(0)
        val openedDestination = AtomicReference<ByteArray>()
        val opened = CountDownLatch(1)
        val proxy = ReceiverSocks5Proxy(
            openTcp = { _, _ -> false },
            sendData = { _, _ -> false },
            closePeerStream = { true },
            openUdp = { id, destination ->
                streamId.set(id)
                openedDestination.set(destination.copyOf())
                opened.countDown()
                true
            },
            sendUdpData = { _, _ -> true },
            maxClients = 4
        )
        val info = proxy.start()
        val control = Socket(info.host, info.port)
        val udpClient = DatagramSocket()
        udpClient.soTimeout = 3_000
        try {
            val input = DataInputStream(control.getInputStream())
            val output = DataOutputStream(control.getOutputStream())
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())

            // RFC 1928 UDP ASSOCIATE request with an unspecified client endpoint.
            output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            val atyp = input.readUnsignedByte()
            assertEquals(1, atyp)
            val relayAddress = ByteArray(4).also(input::readFully)
            val relayPort = input.readUnsignedShort()

            val host = "example.com".toByteArray(Charsets.US_ASCII)
            val port = 53
            val body = byteArrayOf(1, 2, 3, 4)
            val socksDatagram = byteArrayOf(0, 0, 0, 3, host.size.toByte()) + host +
                byteArrayOf(0, port.toByte()) + body
            val relay = InetAddress.getByAddress(relayAddress)
            udpClient.send(DatagramPacket(socksDatagram, socksDatagram.size, relay, relayPort))

            assertTrue("UDP OPEN was not forwarded", opened.await(3, TimeUnit.SECONDS))
            val expectedDestination = byteArrayOf(3, host.size.toByte()) + host + byteArrayOf(0, port.toByte())
            assertArrayEquals(expectedDestination, openedDestination.get())

            val responseBody = byteArrayOf(9, 8, 7)
            proxy.onPeerUdpData(streamId.get(), responseBody)
            val response = ByteArray(512)
            val packet = DatagramPacket(response, response.size)
            udpClient.receive(packet)
            val actual = packet.data.copyOf(packet.length)
            val expectedResponse = byteArrayOf(0, 0, 0, 3, host.size.toByte()) + host +
                byteArrayOf(0, port.toByte()) + responseBody
            assertArrayEquals(expectedResponse, actual)
        } finally {
            control.close()
            udpClient.close()
            proxy.close()
        }
    }
}
