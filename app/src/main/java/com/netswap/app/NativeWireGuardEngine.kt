package com.netswap.app

/**
 * JNI bridge for the packaged BoringTun C ABI. This engine is not yet connected
 * to VpnService or a UDP transport; callers must not treat it as live sharing.
 */
class NativeWireGuardEngine(
    privateKey: ByteArray,
    peerPublicKey: ByteArray,
    keepaliveSeconds: Int = 25,
    index: Int = 1
) : UserspaceWireGuardEngine {
    companion object {
        init { System.loadLibrary("netswap_jni") }
    }

    private var handle: Long
    private var closed = false

    init {
        require(privateKey.size == 32 && peerPublicKey.size == 32) { "WireGuard keys must be 32 bytes" }
        require(keepaliveSeconds in 0..65535)
        handle = nativeCreate(privateKey.copyOf(), peerPublicKey.copyOf(), keepaliveSeconds, index)
        require(handle != 0L) { "Native WireGuard initialization failed" }
    }

    @Synchronized
    override fun encrypt(innerPacket: ByteArray): WireGuardEngineResult = process(innerPacket, false)

    @Synchronized
    override fun decrypt(wireGuardDatagram: ByteArray): WireGuardEngineResult = process(wireGuardDatagram, true)

    private fun process(input: ByteArray, decrypt: Boolean): WireGuardEngineResult {
        if (closed) return WireGuardEngineResult.Failure("Tunnel is closed")
        return try {
            val encoded = if (decrypt) nativeDecapsulate(handle, input) else nativeEncapsulate(handle, input)
            decodeNativeResult(encoded)
        } catch (e: Exception) {
            WireGuardEngineResult.Failure(e.message ?: "Native packet processing failed")
        }
    }

    /** Advance BoringTun timers; any returned network datagram must be sent by UDP transport. */
    @Synchronized
    override fun tick(): WireGuardEngineResult {
        if (closed) return WireGuardEngineResult.Failure("Tunnel is closed")
        return try {
            decodeNativeResult(nativeTick(handle))
        } catch (e: Exception) {
            WireGuardEngineResult.Failure(e.message ?: "Native timer update failed")
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(privateKey: ByteArray, peerKey: ByteArray, keepalive: Int, index: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeEncapsulate(handle: Long, packet: ByteArray): ByteArray
    private external fun nativeDecapsulate(handle: Long, datagram: ByteArray): ByteArray
    private external fun nativeTick(handle: Long): ByteArray

    private fun decodeNativeResult(encoded: ByteArray): WireGuardEngineResult {
        if (encoded.isEmpty()) return WireGuardEngineResult.Failure("Native result is malformed")
        val kind = encoded[0].toInt() and 0xff
        val bytes = encoded.copyOfRange(1, encoded.size)
        return when (kind) {
            0 -> WireGuardEngineResult.NoPacket
            1, 2, 3 -> WireGuardEngineResult.Packet(bytes)
            else -> WireGuardEngineResult.Failure("Unknown native output type")
        }
    }
}
