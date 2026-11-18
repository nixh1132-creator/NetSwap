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

/**
 * Bounded SOCKS5 TCP CONNECT server for sender-side upstream access.
 *
 * It binds only to IPv4 loopback, requires RFC1929 username/password auth,
 * accepts numeric IP destinations only (no ambient-network DNS), and opens
 * every upstream socket through ProtectedUpstreamSockets. This is a building
 * block only: the current WireGuard packet bridge does not yet route packets
 * to this listener, so it is deliberately not started by the VPN service.
 */
class LoopbackSocks5TcpServer(
    private val upstream: ProtectedUpstreamSockets,
    private val username: String,
    private val password: String,
    private val maxClients: Int = 8
) : Closeable {
    private val running = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(
        2, maxClients.coerceIn(1, 16), 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(maxClients.coerceIn(1, 16)),
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

    /** Starts on 127.0.0.1 only. Returns the ephemeral TCP port. */
    @Synchronized
    fun start(): Int {
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
                if (!running.get()) {
                    client.close()
                    break
                }
                try {
                    workers.execute { handleClient(client) }
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) { }
                }
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
            if (request == null) {
                reply(output, 0x08)
                return
            }
            val (destination, port) = request
            val remote = try {
                upstream.openTcp(InetSocketAddress(destination, port), 10_000)
            } catch (_: Exception) {
                reply(output, 0x05)
                return
            }
            remote.use { upstreamSocket ->
                reply(output, 0x00, upstreamSocket.localAddress as? Inet4Address)
                downstream.soTimeout = 0
                upstreamSocket.soTimeout = 0
                relay(downstream, upstreamSocket)
            }
        }
    }

    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        val count = input.read()
        if (version != 5 || count !in 1..255) return false
        var passwordMethodOffered = false
        repeat(count) {
            if (input.read() == 2) passwordMethodOffered = true
        }
        if (!passwordMethodOffered) {
            output.write(byteArrayOf(5, 0xff.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(5, 2))
        output.flush()

        if (input.read() != 1) return false
        val userLength = input.read()
        if (userLength !in 1..255) return false
        val user = readExact(input, userLength)
        val passLength = input.read()
        if (passLength !in 1..255) return false
        val pass = readExact(input, passLength)
        val accepted = user.contentEquals(username.toByteArray(Charsets.UTF_8)) &&
            pass.contentEquals(password.toByteArray(Charsets.UTF_8))
        output.write(byteArrayOf(1, if (accepted) 0 else 1))
        output.flush()
        return accepted
    }

    /** Returns a numeric destination only; domain names are rejected to avoid DNS leakage. */
    private fun readRequest(input: InputStream): Pair<InetAddress, Int>? {
        if (input.read() != 5) return null
        val command = input.read()
        if (input.read() != 0) return null
        val addressType = input.read()
        val address = when (addressType) {
            1 -> InetAddress.getByAddress(readExact(input, 4))
            4 -> InetAddress.getByAddress(readExact(input, 16))
            3 -> {
                val length = input.read()
                if (length !in 1..255) return null
                readExact(input, length) // consume but deliberately reject domains (no DNS leakage)
                readExact(input, 2) // consume destination port
                return null
            }
            else -> return null
        }
        val portBytes = readExact(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        if (command != 1 || port == 0 || address.isAnyLocalAddress || address.isMulticastAddress) {
            return null
        }
        return address to port
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("SOCKS5 request ended early")
            offset += read
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
        first.isDaemon = true
        second.isDaemon = true
        first.start()
        second.start()
        try {
            first.join()
            second.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try { client.close() } catch (_: Exception) { }
            try { remote.close() } catch (_: Exception) { }
        }
    }

    private fun copy(source: Socket, destination: Socket) {
        try {
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
            // Socket closure is the cancellation signal for the paired copier.
        } finally {
            try { destination.shutdownOutput() } catch (_: Exception) { }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try { listener?.close() } catch (_: Exception) { }
        listener = null
        workers.shutdownNow()
        try { acceptThread?.join(1_000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        acceptThread = null
    }
}
