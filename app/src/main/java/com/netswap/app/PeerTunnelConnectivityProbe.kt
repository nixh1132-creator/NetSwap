package com.netswap.app

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Performs a real TCP request through the receiver's loopback SOCKS5 proxy.
 * This verifies the SOCKS -> WebRTC -> provider upstream path, not merely ICE
 * candidate gathering or DataChannel state.
 */
internal object PeerTunnelConnectivityProbe {
    data class Result(val success: Boolean, val detail: String, val httpStatus: String = "")

    fun check(proxyPort: Int): Result {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", proxyPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // SOCKS5, no authentication.
                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) {
                    "SOCKS5 negotiation failed."
                }

                val host = "example.com".toByteArray(Charsets.US_ASCII)
                output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80))
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

                output.write(
                    "GET / HTTP/1.1\\r\\nHost: example.com\\r\\nConnection: close\\r\\nUser-Agent: NetSwap-Probe\\r\\n\\r\\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                val status = readLine(input)
                check(status.startsWith("HTTP/")) { "Provider returned an invalid HTTP response." }
                Result(true, "HTTP response received through provider.", status)
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
