package com.netswap.app

/**
 * Contract for a userspace WireGuard engine that exposes decrypted inner IP packets.
 *
 * This is deliberately an interface only. The current GoBackend does not implement it,
 * and no native BoringTun library is bundled yet.
 */
interface UserspaceWireGuardEngine : AutoCloseable {
    /**
     * Encrypt one complete inner IP packet for the configured peer.
     * The returned datagram, if any, is ready for the authenticated UDP transport.
     */
    fun encrypt(innerPacket: ByteArray): WireGuardEngineResult

    /**
     * Decrypt one received WireGuard UDP datagram.
     * The returned packet, if any, is an inner IP packet and must pass packet policy
     * and quota checks before it is forwarded or injected into another interface.
     */
    fun decrypt(wireGuardDatagram: ByteArray): WireGuardEngineResult

    /**
     * Run protocol timers and return any outbound handshake/keepalive datagram.
     */
    fun tick(): WireGuardEngineResult

    /**
     * Must release native tunnel state, buffers, timers, and callbacks.
     * Implementations must be safe to call more than once.
     */
    override fun close()
}

sealed class WireGuardEngineResult {
    data class Packet(val bytes: ByteArray) : WireGuardEngineResult()
    object NoPacket : WireGuardEngineResult()
    data class Failure(val reason: String) : WireGuardEngineResult()
}
