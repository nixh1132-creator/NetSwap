package com.netswap.app

import java.io.Closeable
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the sender-only loopback SOCKS5 endpoint.
 *
 * The upstream opener must create VPN-protected sockets bound to the selected
 * cellular Network. Credentials are generated per runtime and never persisted.
 * This proxy is a building block for the userspace IP stack; it does not itself
 * consume TUN packets or provide end-to-end internet sharing.
 */
class SenderSocks5Runtime(
    upstream: ProtectedUpstreamSockets,
    quota: SessionTrafficQuota? = null,
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1")
) : Closeable {
    data class Info(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val username = randomCredential()
    private val password = randomCredential()
    private val server = LoopbackSocks5TcpServer(
        upstream = upstream,
        username = username,
        password = password,
        maxClients = 8,
        udpUpstream = upstream,
        quota = quota,
        bindAddress = bindAddress
    )
    @Volatile private var boundPort: Int? = null

    fun start(): Info {
        check(!closed.get()) { "Sender SOCKS runtime is closed." }
        check(started.compareAndSet(false, true)) { "Sender SOCKS runtime already started." }
        return try {
            val port = server.start()
            boundPort = port
            info()
        } catch (failure: Throwable) {
            started.set(false)
            throw failure
        }
    }

    fun info(): Info {
        check(started.get() && !closed.get()) { "Sender SOCKS runtime is not running." }
        return Info(bindAddress.hostAddress ?: "127.0.0.1", checkNotNull(boundPort), username, password)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        boundPort = null
    }

    private fun randomCredential(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
