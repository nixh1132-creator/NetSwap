package com.netswap.app

import android.net.VpnService
import android.net.Network
import android.util.Base64
import java.io.Closeable

/**
 * Creates the packet bridge only from a validated participant config.
 * The caller owns the TUN device and must stop/close the returned runtime.
 *
 * This factory does not provision a gateway or make a provisioning session usable.
 */
object PacketTunnelRuntimeFactory {
    fun create(
        vpnService: VpnService,
        tunDevice: TunPacketDevice,
        config: TunnelSessionConfig,
        privateKey: ByteArray,
        senderUpstreamNetwork: Network? = null,
        onGateResult: (PacketGateResult, TrafficDirection) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ): Runtime {
        require(privateKey.size == 32) { "WireGuard private key must be 32 bytes." }
        com.wireguard.crypto.Key.fromBase64(config.remotePublicKey)
        val remotePublicKeyBytes = Base64.decode(config.remotePublicKey, Base64.NO_WRAP)
        require(remotePublicKeyBytes.size == 32) { "WireGuard peer public key must be 32 bytes." }
        if (config.isSender) {
            requireNotNull(senderUpstreamNetwork) {
                "Sender tunnel requires a validated cellular Internet network."
            }
        } else {
            require(senderUpstreamNetwork == null) {
                "Receiver tunnel must not bind sender cellular upstream."
            }
        }
        val upstreamSockets = if (config.isSender) {
            ProtectedUpstreamSockets(vpnService, requireNotNull(senderUpstreamNetwork))
        } else null
        val engine = NativeWireGuardEngine(
            privateKey = privateKey.copyOf(),
            peerPublicKey = remotePublicKeyBytes
        )
        try {
            val bridge = PacketTunnelBridge(
                tunDevice = tunDevice,
                engine = engine,
                endpoint = config.endpoint,
                protectSocket = { socket -> vpnService.protect(socket) },
                quotaBytes = config.quotaBytes,
                onGateResult = onGateResult,
                onFailure = onFailure
            )
            return Runtime(engine, bridge, upstreamSockets)
        } catch (failure: Exception) {
            upstreamSockets?.close()
            engine.close()
            throw failure
        }
    }

    class Runtime internal constructor(
        private val engine: NativeWireGuardEngine,
        private val bridge: PacketTunnelBridge,
        private val upstreamSockets: ProtectedUpstreamSockets?
    ) : Closeable {
        fun start() = bridge.start()
        fun revoke() = bridge.revoke()
        fun snapshot(): TrafficQuotaSnapshot = bridge.snapshot()

        override fun close() {
            try { bridge.close() } finally {
                try { upstreamSockets?.close() } finally { engine.close() }
            }
        }
    }
}
