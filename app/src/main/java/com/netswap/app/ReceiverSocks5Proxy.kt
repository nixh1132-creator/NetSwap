package com.netswap.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receiver-side localhost SOCKS5 CONNECT adapter for the NSWP TCP stream.
 *
 * This is an app-level proxy (not yet a device-wide VPN/TUN adapter). It binds
 * only to 127.0.0.1, resolves hostnames on the receiver, and sends numeric
 * destinations through the authenticated peer data channel.
 */
class ReceiverSocks5Proxy(
    private val openTcp: (Int, ByteArray) -> Boolean,
    private val sendData: (Int, ByteArray) -> Boolean,
    private val closePeerStream: (Int) -> Boolean,
    private val maxClients: Int = 8
) : Closeable {
    data class Info(val host: String, val port: Int)

    private val closed = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val clients = ConcurrentHashMap<Int, Socket>()
    private val writers = ConcurrentHashMap<Int, DataOutputStream>()
    private val workers = Executors.newFixedThreadPool(maxClients)
    @Volatile private var server: ServerSocket? = null

    init { require(maxClients in 1..16) }

    fun start(): Info {
        check(!closed.get())
        check(server == null) { "Receiver proxy already started." }
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), maxClients)
        server = listener
        workers.execute {
            while (!closed.get()) {
                try {
                    val socket = listener.accept()
                    if (clients.size >= maxClients) socket.close()
                    else workers.execute { handle(socket) }
                } catch (_: IOException) {
                    if (!closed.get()) close()
                }
            }
        }
        return Info("127.0.0.1", listener.localPort)
    }

    /** Called by the peer transport when remote TCP data arrives. */
    fun onPeerData(streamId: Int, payload: ByteArray) {
        val output = writers[streamId] ?: return
        try {
            synchronized(output) { output.write(payload); output.flush() }
        } catch (_: IOException) {
            closeLocal(streamId, notifyPeer = true)
        }
    }

    /** Called when the remote endpoint reports an asynchronous connect/stream error. */
    fun onPeerError(streamId: Int) = closeLocal(streamId, notifyPeer = false)

    private fun handle(socket: Socket) {
        var streamId = 0
        try {
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            require(input.readUnsignedByte() == 5) { "Unsupported SOCKS version." }
            val methods = input.readUnsignedByte()
            require(methods in 1..32)
            val offered = ByteArray(methods)
            input.readFully(offered)
            if (0 !in offered) {
                output.write(byteArrayOf(5, 0xff.toByte())); output.flush()
                return
            }
            output.write(byteArrayOf(5, 0)); output.flush()
            require(input.readUnsignedByte() == 5)
            val command = input.readUnsignedByte()
            input.readUnsignedByte() // reserved
            val atyp = input.readUnsignedByte()
            if (command != 1) { reply(output, 7); return }
            val address = when (atyp) {
                1 -> ByteArray(4).also(input::readFully)
                4 -> ByteArray(16).also(input::readFully)
                3 -> {
                    val length = input.readUnsignedByte()
                    require(length in 1..253)
                    val host = ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII)
                    InetAddress.getAllByName(host).first().address
                }
                else -> { reply(output, 8); return }
            }
            val port = input.readUnsignedShort()
            if (port == 0) { reply(output, 8); return }
            val destination = byteArrayOf(if (address.size == 4) 1 else 4) +
                address + byteArrayOf((port shr 8).toByte(), port.toByte())
            streamId = allocateId()
            clients[streamId] = socket
            writers[streamId] = output
            if (!openTcp(streamId, destination)) {
                reply(output, 5)
                closeLocal(streamId, notifyPeer = true)
                return
            }
            reply(output, 0)
            socket.soTimeout = 0
            val buffer = ByteArray(12 * 1024)
            while (!closed.get() && !socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (!sendData(streamId, buffer.copyOf(count))) break
            }
        } catch (_: Exception) {
            if (streamId == 0) runCatching { socket.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) }
        } finally {
            if (streamId != 0) closeLocal(streamId, notifyPeer = true)
            else runCatching { socket.close() }
        }
    }

    private fun reply(output: DataOutputStream, code: Int) {
        output.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun allocateId(): Int {
        repeat(Int.MAX_VALUE) {
            val id = nextId.getAndUpdate { if (it >= Int.MAX_VALUE - 1) 1 else it + 1 }
            if (id > 0 && !clients.containsKey(id)) return id
        }
        throw IOException("No peer stream identifiers available.")
    }

    private fun closeLocal(streamId: Int, notifyPeer: Boolean) {
        writers.remove(streamId)
        clients.remove(streamId)?.let { runCatching { it.close() } }
        if (notifyPeer) closePeerStream(streamId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        clients.keys.toList().forEach { closeLocal(it, notifyPeer = true) }
        workers.shutdownNow()
    }
}
