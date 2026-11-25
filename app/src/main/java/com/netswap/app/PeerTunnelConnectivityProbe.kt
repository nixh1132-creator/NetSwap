package com.netswap.app

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Performs a real HTTPS request through the receiver's loopback SOCKS5 proxy.
 * This verifies SOCKS -> WebRTC -> provider upstream, including provider-side DNS,
 * TCP and TLS. It does not treat ICE or DataChannel connection as internet access.
 */
internal object PeerTunnelConnectivityProbe {
    data class Result(val success: Boolean, val detail: String, val httpStatus: String = "")

    fun check(proxyPort: Int): Result {
        val targets = listOf(
            "connectivitycheck.gstatic.com" to "/generate_204",
            "example.com" to "/"
        )
        var lastFailure = "No HTTPS probe target completed."
        for ((host, path) in targets) {
            val result = checkHttpsTarget(proxyPort, host, path)
            if (result.success) return result
            lastFailure = "$host: ${result.detail}"
        }
        return Result(false, lastFailure)
    }

    private fun checkHttpsTarget(proxyPort: Int, host: String, path: String): Result {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", proxyPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // SOCKS5, no authentication; pass the hostname so DNS runs on the provider.
                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) {
                    "SOCKS5 negotiation failed."
                }

                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                output.write(byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()) + hostBytes + byteArrayOf(1, -69))
                output.flush()

                check(input.readUnsignedByte() == 5) { "Invalid SOCKS5 connect response." }
                val reply = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val atyp = input.readUnsignedByte()
                check(reply == 0) { "SOCKS5 CONNECT failed (reply $reply)." }
                when (atyp) {
                    1 -> input.skipBytes(4)
                    4 -> input.skipBytes(16)
                    3 -> input.skipBytes(input.readUnsignedByte())
                    else -> error("Invalid SOCKS5 bound address.")
                }
                input.readUnsignedShort() // bound port

                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, 443, true) as SSLSocket
                tls.use { secure ->
                    secure.soTimeout = READ_TIMEOUT_MS
                    secure.startHandshake()
                    secure.outputStream.write(
                        "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: NetSwap-Probe\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    secure.outputStream.flush()
                    val status = readLine(DataInputStream(secure.inputStream))
                    check(status.startsWith("HTTP/")) { "Provider returned an invalid HTTPS response." }
                    val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                    check(code != null && code in 200..399) { "HTTPS probe returned $status." }
                    Result(true, "HTTPS response received through provider ($host).", status)
                }
            }
        } catch (failure: Exception) {
            Result(false, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun readLine(input: DataInputStream): String {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < 2048) {
            val value = input.read()
            if (value < 0) break
            if (value == 10) break
            if (value != 13) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
}
