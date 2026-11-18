package com.netswap.app

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI

/**
 * Validated, participant-specific runtime settings returned by the trusted backend.
 * A session is unusable until the relay has marked it ready or active.
 */
data class TunnelSessionConfig(
    val sessionId: String,
    val endpoint: InetSocketAddress,
    val localTunnelIp: String,
    val remoteTunnelIp: String,
    val remotePublicKey: String,
    val quotaBytes: Long,
    val isSender: Boolean
) {
    companion object {
        fun fromJson(row: JSONObject, userId: String, localPublicKey: String): TunnelSessionConfig {
            val status = row.optString("status")
            require(status == "ready" || status == "active") {
                "Secure gateway is not ready yet."
            }

            val senderId = row.getString("sender_id")
            val receiverId = row.getString("receiver_id")
            val senderKey = row.optString("sender_public_key")
            val receiverKey = row.optString("receiver_public_key")
            val isSender = userId == senderId
            val isReceiver = userId == receiverId
            require(isSender xor isReceiver) { "This account is not a participant in this tunnel." }

            val expectedLocalKey = if (isSender) senderKey else receiverKey
            val remoteKey = if (isSender) receiverKey else senderKey
            require(expectedLocalKey.isNotBlank() && expectedLocalKey == localPublicKey) {
                "This phone's registered tunnel key does not match."
            }
            require(remoteKey.isNotBlank()) { "Your friend's tunnel key is not registered yet." }

            val gatewayKey = row.optString("gateway_public_key")
            require(gatewayKey.isNotBlank()) { "Secure gateway public key is not configured yet." }

            val localIp = row.optString(if (isSender) "sender_tunnel_ip" else "receiver_tunnel_ip")
            val remoteIp = row.optString(if (isSender) "receiver_tunnel_ip" else "sender_tunnel_ip")
            require(isIpv4(localIp) && isIpv4(remoteIp) && localIp != remoteIp) {
                "Tunnel IP allocation is incomplete or invalid."
            }

            val endpointText = row.optString("gateway_endpoint")
            require(endpointText.isNotBlank()) { "Secure gateway endpoint is not configured yet." }
            val uri = try { URI("udp://$endpointText") } catch (_: Exception) {
                throw IllegalArgumentException("Secure gateway endpoint is invalid.")
            }
            val host = uri.host
            val port = uri.port
            require(!host.isNullOrBlank() && port in 1..65535 && uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                "Secure gateway endpoint is invalid."
            }

            val quota = row.optLong("quota_bytes", 0L)
            require(quota > 0L) { "Tunnel quota is invalid." }
            return TunnelSessionConfig(
                sessionId = row.getString("id"),
                endpoint = InetSocketAddress.createUnresolved(host, port),
                localTunnelIp = localIp,
                remoteTunnelIp = remoteIp,
                remotePublicKey = gatewayKey,
                quotaBytes = quota,
                isSender = isSender
            )
        }

        private fun isIpv4(value: String): Boolean {
            val parts = value.split(".")
            return parts.size == 4 && parts.all {
                it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull()?.let { number -> number in 0..255 } == true)
            }
        }
    }
}
