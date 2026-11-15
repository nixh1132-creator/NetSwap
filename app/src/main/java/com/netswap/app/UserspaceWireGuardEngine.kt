package com.netswap.app

/**
 * Contract for a userspace WireGuard engine.
 *
 * NetworkDatagram values must be sent to the configured peer over protected UDP.
 * TunnelPacket values are decrypted inner IP packets and must pass packet policy
 * and quota checks before they are injected into a TUN interface.
 */
interface UserspaceWireGuardEngine : AutoCloseable {
    fun encrypt(innerPacket: ByteArray): WireGuardEngineResult
    fun decrypt(wireGuardDatagram: ByteArray): WireGuardEngineResult
    fun tick(): WireGuardEngineResult
    /** Release native state. Implementations must tolerate repeated close calls. */
    override fun close()
}

sealed class WireGuardEngineResult {
    data class NetworkDatagram(val bytes: ByteArray) : WireGuardEngineResult()
    data class TunnelPacket(val bytes: ByteArray, val ipVersion: Int) : WireGuardEngineResult()
    object NoPacket : WireGuardEngineResult()
    data class Failure(val reason: String) : WireGuardEngineResult()
}

/** Decode the stable one-byte output-kind prefix returned by the JNI C ABI. */
internal fun decodeWireGuardNativeResult(encoded: ByteArray): WireGuardEngineResult {
    if (encoded.isEmpty()) return WireGuardEngineResult.Failure("Native result is malformed")
    val kind = encoded[0].toInt() and 0xff
    val bytes = encoded.copyOfRange(1, encoded.size)
    return when (kind) {
        0 -> if (bytes.isEmpty()) WireGuardEngineResult.NoPacket
             else WireGuardEngineResult.Failure("Unexpected bytes for empty native output")
        1 -> if (bytes.isNotEmpty()) WireGuardEngineResult.NetworkDatagram(bytes)
             else WireGuardEngineResult.Failure("Empty network datagram")
        2 -> if (bytes.isNotEmpty()) WireGuardEngineResult.TunnelPacket(bytes, 4)
             else WireGuardEngineResult.Failure("Empty IPv4 tunnel packet")
        3 -> if (bytes.isNotEmpty()) WireGuardEngineResult.TunnelPacket(bytes, 6)
             else WireGuardEngineResult.Failure("Empty IPv6 tunnel packet")
        else -> WireGuardEngineResult.Failure("Unknown native output type")
    }
}
