package com.netswap.app

/**
 * JNI bridge for the packaged BoringTun C ABI. It is not yet connected to
 * VpnService or a UDP transport; do not treat it as live internet sharing.
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
            decodeWireGuardNativeResult(encoded)
        } catch (e: Exception) {
            WireGuardEngineResult.Failure(e.message ?: "Native packet processing failed")
        }
    }

    /** Advance BoringTun timers; any returned network datagram must be sent by UDP transport. */
    @Synchronized
    override fun tick(): WireGuardEngineResult {
        if (closed) return WireGuardEngineResult.Failure("Tunnel is closed")
        return try {
            decodeWireGuardNativeResult(nativeTick(handle))
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
    private external fun nativeDecapsulate(handle: Long, packet: ByteArray): ByteArray
    private external fun nativeTick(handle: Long): ByteArray
}
