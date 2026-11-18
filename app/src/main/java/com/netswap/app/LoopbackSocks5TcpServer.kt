package com.netswap.app

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/** Loopback-only authenticated SOCKS5 server; upstream sockets must be protected by the caller. */
fun interface TcpUpstreamOpener {
    fun openTcp(destination: InetSocketAddress, connectTimeoutMs: Int): Socket
}

private data class Socks5Request(val command: Int, val address: InetAddress, val port: Int)

class LoopbackSocks5TcpServer(
    private val upstream: TcpUpstreamOpener,
    private val username: String,
    private val password: String,
    private val maxClients: Int = 8,
    private val udpUpstream: UdpUpstreamOpener? = null
) : Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val clientLimit = maxClients.coerceIn(1, 16)
    private val workers = ThreadPoolExecutor(
        minOf(2, clientLimit), clientLimit, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(clientLimit),
        { task -> Thread(task, "NetSwap-SOCKS-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    init {
        require(username.isNotBlank() && username.toByteArray(Charsets.UTF_8).size <= 255)
        require(password.isNotBlank() && password.toByteArray(Charsets.UTF_8).size <= 255)
        require(maxClients in 1..16)
    }

    @Synchronized
    fun start(): Int {
        check(!closed.get()) { "SOCKS5 server is closed" }
        check(running.compareAndSet(false, true)) { "SOCKS5 server already started" }
        try {
            val socket = ServerSocket()
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), maxClients)
            listener = socket
            acceptThread = Thread({ acceptLoop(socket) }, "NetSwap-SOCKS-accept").apply {
                isDaemon = true
                start()
            }
            return socket.localPort
        } catch (failure: Throwable) {
            running.set(false)
            try { listener?.close() } catch (_: Exception) { }
            listener = null
            throw failure
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                if (!running.get()) { client.close(); break }
                activeClients.add(client)
                try {
                    workers.execute {
                        try { handleClient(client) }
                        finally { activeClients.remove(client) }
                    }
                } catch (_: Exception) { try { client.close() } catch (_: Exception) { } }
            } catch (_: Exception) {
                if (running.get()) close()
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { downstream ->
            downstream.soTimeout = 15_000
            downstream.tcpNoDelay = true
            val input = downstream.getInputStream()
            val output = downstream.getOutputStream()
            if (!authenticate(input, output)) return
            val request = try { readRequest(input) } catch (_: Exception) { null }
            if (request == null) { reply(output, 0x08); return }
            when (request.command) {
                3 -> {
                    val udp = udpUpstream
                    if (udp == null || !request.address.isAnyLocalAddress && !request.address.isLoopbackAddress) {
                        reply(output, 0x07)
                        return
                    }
                    downstream.soTimeout = 0
                    LoopbackSocks5UdpAssociation(udp).use { it.run(downstream, output) }
                }
                1 -> {
                    if (request.port == 0 || request.address.isAnyLocalAddress || request.address.isMulticastAddress) {
                        reply(output, 0x08); return
                    }
                    val remote = try {
                        upstream.openTcp(InetSocketAddress(request.address, request.port), 10_000)
                    } catch (_: Exception) { reply(output, 0x05); return }
                    remote.use { upstreamSocket ->
                        reply(output, 0x00, upstreamSocket.localAddress as? Inet4Address)
                        downstream.soTimeout = 0
                        upstreamSocket.soTimeout = 0
                        relay(downstream, upstreamSocket)
                    }
                }
                else -> reply(output, 0x07)
            }
        }
    }

    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        if (input.read() != 5) return false
        val count = input.read()
        if (count !in 1..255) return false
        var offered = false
        repeat(count) { if (input.read() == 2) offered = true }
        if (!offered) {
            output.write(byteArrayOf(5, 0xff.toByte())); output.flush(); return false
        }
        output.write(byteArrayOf(5, 2)); output.flush()
        if (input.read() != 1) return false
        val userLength = input.read()
        if (userLength !in 1..255) return false
        val user = readExact(input, userLength)
        val passLength = input.read()
        if (passLength !in 1..255) return false
        val pass = readExact(input, passLength)
        val accepted = user.contentEquals(username.toByteArray(Charsets.UTF_8)) &&
            pass.contentEquals(password.toByteArray(Charsets.UTF_8))
        output.write(byteArrayOf(1, if (accepted) 0 else 1)); output.flush()
        return accepted
    }

    /** Numeric-only parsing prevents ambient DNS resolution and DNS leakage. */
    private fun readRequest(input: InputStream): Socks5Request? {
        if (input.read() != 5) return null
        val command = input.read()
        if (command !in 1..3 || input.read() != 0) return null
        val addressType = input.read()
        val address = when (addressType) {
            1 -> InetAddress.getByAddress(readExact(input, 4))
            4 -> InetAddress.getByAddress(readExact(input, 16))
            3 -> {
                val length = input.read()
                if (length !in 1..255) return null
                readExact(input, length)
                readExact(input, 2)
                return null
            }
            else -> return null
        }
        val portBytes = readExact(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        if (address.isMulticastAddress) return null
        if (command == 1 && (port == 0 || address.isAnyLocalAddress)) return null
        if (command == 3 && !address.isAnyLocalAddress && !address.isLoopbackAddress) return null
        return Socks5Request(command, address, port)
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("SOCKS5 request ended early")
            offset += count
        }
        return bytes
    }

    private fun reply(output: OutputStream, status: Int, boundAddress: Inet4Address? = null) {
        val address = boundAddress?.address ?: byteArrayOf(0, 0, 0, 0)
        output.write(byteArrayOf(5, status.toByte(), 0, 1) + address + byteArrayOf(0, 0))
        output.flush()
    }

    private fun relay(client: Socket, remote: Socket) {
        val first = Thread({ copy(client, remote) }, "NetSwap-SOCKS-up")
        val second = Thread({ copy(remote, client) }, "NetSwap-SOCKS-down")
        first.isDaemon = true; second.isDaemon = true
        first.start(); second.start()
        try { first.join(); second.join() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { try { client.close() } catch (_: Exception) { }; try { remote.close() } catch (_: Exception) { } }
    }

    private fun copy(source: Socket, destination: Socket) {
        try {
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count); output.flush()
            }
        } catch (_: Exception) { }
        finally { try { destination.shutdownOutput() } catch (_: Exception) { } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try { listener?.close() } catch (_: Exception) { }
        listener = null
        activeClients.forEach { try { it.close() } catch (_: Exception) { } }
        activeClients.clear()
        workers.shutdownNow()
        try { acceptThread?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        acceptThread = null
    }
}
