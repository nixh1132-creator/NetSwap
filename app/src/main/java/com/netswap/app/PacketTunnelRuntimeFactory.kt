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
        val sessionQuota = SessionTrafficQuota(config.quotaBytes)
        val upstreamSockets = if (config.isSender) {
            ProtectedUpstreamSockets(vpnService, requireNotNull(senderUpstreamNetwork))
        } else null
        // The packet bridge is the authoritative session-accounting boundary.
        // SOCKS is a downstream adapter for the future TUN-to-socket translator;
        // passing the same quota here would count the same payload twice.
        val senderProxy = upstreamSockets?.let { SenderSocks5Runtime(it) }
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
                sharedQuota = sessionQuota,
                onGateResult = onGateResult,
                onFailure = onFailure
            )
            return Runtime(engine, bridge, upstreamSockets, senderProxy)
        } catch (failure: Exception) {
            senderProxy?.close()
            upstreamSockets?.close()
            engine.close()
            throw failure
        }
    }

    class Runtime internal constructor(
        private val engine: NativeWireGuardEngine,
        private val bridge: PacketTunnelBridge,
        private val upstreamSockets: ProtectedUpstreamSockets?,
        private val senderProxy: SenderSocks5Runtime?
    ) : Closeable {
        /** Starts the sender's loopback proxy before the WireGuard packet bridge. */
        fun start() {
            senderProxy?.start()
            try {
                bridge.start()
            } catch (failure: Exception) {
                close()
                throw failure
            }
        }

        fun senderProxyInfo(): SenderSocks5Runtime.Info? = senderProxy?.info()
        fun revoke() {
            senderProxy?.close()
            upstreamSockets?.close()
            bridge.revoke()
        }
        fun snapshot(): TrafficQuotaSnapshot = bridge.snapshot()

        override fun close() {
            try { senderProxy?.close() } finally {
                try { bridge.close() } finally {
                    try { upstreamSockets?.close() } finally { engine.close() }
                }
            }
        }
    }
}
