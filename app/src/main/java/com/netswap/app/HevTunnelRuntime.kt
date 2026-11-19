package com.netswap.app

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the JNI lifecycle for the pinned Hev SOCKS5 tunnel engine.
 * The Java JNI binding is resolved reflectively so Kotlin compilation does
 * not depend on javac source-order visibility.
 */
class HevTunnelRuntime(
    private val context: Context
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var started = false

    private val binding: Class<*> by lazy {
        Class.forName("hev.htproxy.TProxyService")
    }

    private fun invokeBoolean(method: String, vararg args: Any): Boolean {
        val signature = when (method) {
            "TProxyStartService" -> arrayOf(String::class.java, Int::class.javaPrimitiveType)
            else -> emptyArray()
        }
        val result = binding.getMethod(method, *signature).invoke(null, *args)
        return result as Boolean
    }

    @Synchronized
    fun start(packetDescriptor: ParcelFileDescriptor, socksHost: String, socksPort: Int): Boolean {
        check(!closed.get()) { "Hev runtime is closed." }
        check(!started) { "Hev runtime is already started." }
        require(socksHost == "127.0.0.1" || socksHost == "::1") {
            "Hev SOCKS endpoint must be loopback-only."
        }
        require(socksPort in 1..65535)
        val config = File(context.filesDir, "netswap-hev.yml")
        config.writeText(
            """
            tunnel:
              name: netswap0
              mtu: 1400
              multi-queue: false
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'off'
            socks5:
              address: $socksHost
              port: $socksPort
              udp: 'tcp'
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              connect-timeout: 10000
              tcp-read-write-timeout: 120000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        val owned = ParcelFileDescriptor.dup(packetDescriptor.fileDescriptor)
        val ok = try {
            invokeBoolean("TProxyStartService", config.absolutePath, owned.fd)
        } catch (failure: Exception) {
            owned.close()
            throw failure
        }
        if (!ok) {
            owned.close()
            error("Hev tunnel refused to start.")
        }
        descriptor = owned
        started = true
        return true
    }

    fun isRunning(): Boolean =
        !closed.get() && started && invokeBoolean("TProxyIsRunning")

    fun stats(): LongArray =
        if (isRunning()) binding.getMethod("TProxyGetStats").invoke(null) as LongArray
        else longArrayOf(0, 0, 0, 0)

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (started) runCatching { invokeBoolean("TProxyStopService") }
        started = false
        runCatching { descriptor?.close() }
        descriptor = null
    }
}
