package com.netswap.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunnelSessionConfigTest {
    private fun row(status: String = "ready", endpoint: String = "198.51.100.10:51820") =
        JSONObject()
            .put("id", "session-1")
            .put("sender_id", "user-a")
            .put("receiver_id", "user-b")
            .put("sender_public_key", "sender-public")
            .put("receiver_public_key", "receiver-public")
            .put("sender_tunnel_ip", "10.77.0.2")
            .put("receiver_tunnel_ip", "10.77.0.3")
            .put("gateway_endpoint", endpoint)
            .put("status", status)
            .put("quota_bytes", 104857600L)

    @Test fun resolvesSenderPerspectiveOnlyFromReadySession() {
        val config = TunnelSessionConfig.fromJson(row(), "user-a", "sender-public")
        assertEquals("10.77.0.2", config.localTunnelIp)
        assertEquals("10.77.0.3", config.remoteTunnelIp)
        assertEquals("receiver-public", config.remotePublicKey)
        assertEquals("198.51.100.10", config.endpoint.hostString)
        assertEquals(51820, config.endpoint.port)
        assertEquals(104857600L, config.quotaBytes)
        assertEquals(true, config.isSender)
    }

    @Test fun resolvesReceiverPerspective() {
        val config = TunnelSessionConfig.fromJson(row(), "user-b", "receiver-public")
        assertEquals("10.77.0.3", config.localTunnelIp)
        assertEquals("10.77.0.2", config.remoteTunnelIp)
        assertEquals("sender-public", config.remotePublicKey)
        assertEquals(false, config.isSender)
    }

    @Test fun rejectsProvisioningSession() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelSessionConfig.fromJson(row(status = "provisioning"), "user-a", "sender-public")
        }
    }

    @Test fun rejectsMissingGatewayEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelSessionConfig.fromJson(row(endpoint = ""), "user-a", "sender-public")
        }
    }

    @Test fun rejectsMismatchedLocalKeyAndNonParticipant() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelSessionConfig.fromJson(row(), "user-a", "wrong-key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelSessionConfig.fromJson(row(), "user-c", "sender-public")
        }
    }
}
