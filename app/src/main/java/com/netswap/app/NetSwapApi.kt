package com.netswap.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
data class Friend(val userId: String, val uid: String, val displayName: String?)
data class ShareRequest(val id: String, val senderId: String, val receiverId: String, val quotaBytes: Long, val usedBytes: Long, val status: String, val createdAt: String, val senderName: String, val receiverName: String)
data class PeerSignal(val id: String, val requestId: String, val fromUserId: String, val type: String, val payload: JSONObject, val createdAt: String)

object NetSwapApi {
    private val base = BuildConfig.SUPABASE_URL
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    private fun call(path: String, method: String, token: String? = null, body: JSONObject? = null, prefer: String? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", apiKey)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                val msg = j.optString("msg").ifBlank { j.optString("message").ifBlank { j.optString("error_description").ifBlank { j.optString("error").ifBlank { "Request failed ($code)" } } } }
                throw IllegalStateException("$msg (HTTP $code)")
            }
            return text
        } finally { c.disconnect() }
    }

    fun signUp(email: String, password: String): JSONObject =
        JSONObject(call("/auth/v1/signup", "POST", body = JSONObject().put("email", email).put("password", password)))

    fun signIn(email: String, password: String): AuthSession {
        val j = JSONObject(call("/auth/v1/token?grant_type=password", "POST",
            body = JSONObject().put("email", email).put("password", password)))
        val u = j.getJSONObject("user")
        return AuthSession(j.getString("access_token"), j.optString("refresh_token"), u.getString("id"), u.optString("email", email))
    }

    fun refresh(refreshToken: String): AuthSession {
        val j = JSONObject(call("/auth/v1/token?grant_type=refresh_token", "POST",
            body = JSONObject().put("refresh_token", refreshToken)))
        val u = j.getJSONObject("user")
        return AuthSession(j.getString("access_token"), j.optString("refresh_token", refreshToken), u.getString("id"), u.optString("email", ""))
    }

    fun displayName(session: AuthSession): String {
        val raw = call("/rest/v1/profiles?select=display_name&id=eq." + enc(session.userId), "GET", session.accessToken)
        val rows = JSONArray(raw)
        return if (rows.length() > 0) rows.getJSONObject(0).optString("display_name").trim() else ""
    }

    fun updateDisplayName(session: AuthSession, name: String) {
        val clean = name.trim()
        require(clean.length in 2..24) { "Username 2 se 24 characters ka hona chahiye." }
        require(clean.matches(Regex("[A-Za-z0-9_ ]+"))) { "Username mein letters, numbers, space aur underscore hi use karo." }
        call("/rest/v1/profiles?id=eq." + enc(session.userId), "PATCH", session.accessToken,
            JSONObject().put("display_name", clean), "return=minimal")
    }

    fun profileUid(session: AuthSession): String {
        val raw = call("/rest/v1/profiles?select=uid&id=eq." + enc(session.userId), "GET", session.accessToken)
        val rows = JSONArray(raw)
        return if (rows.length() > 0) rows.getJSONObject(0).optString("uid") else ""
    }

    fun lookupUid(session: AuthSession, uid: String): Friend? {
        val raw = call("/rest/v1/rpc/lookup_netswap_user_by_uid", "POST", session.accessToken,
            JSONObject().put("p_uid", uid.trim().uppercase()))
        val rows = JSONArray(raw)
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        return Friend(row.getString("user_id"), row.getString("uid"), row.optString("display_name").ifBlank { null })
    }

    fun sendRequest(session: AuthSession, receiverId: String, quotaBytes: Long) {
        call("/rest/v1/sharing_requests", "POST", session.accessToken,
            JSONObject().put("sender_id", session.userId).put("receiver_id", receiverId).put("quota_bytes", quotaBytes),
            "return=minimal")
    }

    fun deleteRequest(session: AuthSession, requestId: String) {
        call("/rest/v1/sharing_requests?id=eq." + enc(requestId), "DELETE", session.accessToken, prefer = "return=minimal")
    }

    fun requests(session: AuthSession): List<ShareRequest> {
        val rows = JSONArray(call("/rest/v1/rpc/list_netswap_requests", "POST", session.accessToken, JSONObject()))
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            ShareRequest(
                r.getString("id"), r.getString("sender_id"), r.getString("receiver_id"),
                r.getLong("quota_bytes"), r.optLong("used_bytes"), r.getString("status"),
                r.optString("created_at"),
                r.optString("sender_name").ifBlank { "NetSwap user" },
                r.optString("receiver_name").ifBlank { "NetSwap user" }
            )
        }
    }

    fun tunnelSessionConfig(session: AuthSession, requestId: String, localPublicKey: String): TunnelSessionConfig {
        val filter = "/rest/v1/tunnel_sessions?select=id,sender_id,receiver_id,sender_public_key,receiver_public_key,sender_tunnel_ip,receiver_tunnel_ip,gateway_endpoint,gateway_public_key,status,quota_bytes&sharing_request_id=eq.${enc(requestId)}"
        val rows = JSONArray(call(filter, "GET", session.accessToken))
        require(rows.length() == 1) { "Secure tunnel session has not been created yet." }
        return TunnelSessionConfig.fromJson(rows.getJSONObject(0), session.userId, localPublicKey)
    }

    /** Fetches authenticated ICE servers; TURN credentials are never stored in the APK. */
    fun peerIceServers(session: AuthSession): JSONArray {
        val response = JSONObject(call(
            "/functions/v1/netswap-ice-config",
            "POST",
            session.accessToken,
            JSONObject()
        ))
        val servers = response.optJSONArray("iceServers")
            ?: throw IllegalStateException("TURN configuration is missing.")
        require(servers.length() in 1..8) { "Invalid ICE server configuration." }

        // Do not mistake a STUN-only response for a working TURN relay.
        val hasAuthenticatedTurn = (0 until servers.length()).any { index ->
            val server = servers.optJSONObject(index) ?: return@any false
            val urls = server.opt("urls")
            val turnUrlFound = when (urls) {
                is String -> urls.startsWith("turn:", ignoreCase = true) ||
                    urls.startsWith("turns:", ignoreCase = true)
                is JSONArray -> (0 until urls.length()).any { urlIndex ->
                    val url = urls.optString(urlIndex)
                    url.startsWith("turn:", ignoreCase = true) ||
                        url.startsWith("turns:", ignoreCase = true)
                }
                else -> false
            }
            turnUrlFound && server.optString("username").isNotBlank() &&
                server.optString("credential").isNotBlank()
        }
        check(hasAuthenticatedTurn) {
            "TURN relay credentials are missing. Check Turnix and Metered provider settings."
        }
        return servers
    }

    fun registerTunnelPublicKey(session: AuthSession, requestId: String, publicKey: String): JSONObject {
        return JSONObject(call(
            "/functions/v1/register-tunnel-public-key",
            "POST",
            session.accessToken,
            JSONObject().put("request_id", requestId).put("public_key", publicKey)
        ))
    }

    fun respond(session: AuthSession, requestId: String, status: String) {
        require(status == "approved" || status == "rejected" || status == "cancelled")
        val body = JSONObject().put("status", status).put("responded_at", java.time.Instant.now().toString())
        call("/rest/v1/sharing_requests?id=eq.${enc(requestId)}", "PATCH", session.accessToken, body, "return=minimal")
    }


    /**
     * Sends one short-lived WebRTC signaling message to the other approved participant.
     * Payloads are SDP/ICE metadata only; user traffic must never be sent through this table.
     */
    fun sendPeerSignal(
        session: AuthSession,
        requestId: String,
        peerUserId: String,
        type: String,
        payload: JSONObject
    ) {
        require(type in setOf("offer", "answer", "ice", "hangup")) { "Unsupported signaling message." }
        require(peerUserId.isNotBlank() && peerUserId != session.userId) { "Invalid peer." }
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 12 * 1024) {
            "Signaling message is too large."
        }
        call(
            "/rest/v1/netswap_signals",
            "POST",
            session.accessToken,
            JSONObject()
                .put("request_id", requestId)
                .put("from_user_id", session.userId)
                .put("to_user_id", peerUserId)
                .put("message_type", type)
                .put("payload", payload),
            "return=minimal"
        )
    }

    /** Reads pending, unexpired signaling messages addressed to the signed-in user. */
    fun pendingPeerSignals(session: AuthSession, requestId: String): List<PeerSignal> {
        val filter = "select=id,request_id,from_user_id,message_type,payload,created_at" +
            "&request_id=eq.${enc(requestId)}&to_user_id=eq.${enc(session.userId)}" +
            "&expires_at=gt.${enc(java.time.Instant.now().toString())}&order=created_at.asc&limit=100"
        val rows = JSONArray(call("/rest/v1/netswap_signals?$filter", "GET", session.accessToken))
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            PeerSignal(
                id = row.getString("id"),
                requestId = row.getString("request_id"),
                fromUserId = row.getString("from_user_id"),
                type = row.getString("message_type"),
                payload = row.getJSONObject("payload"),
                createdAt = row.optString("created_at")
            )
        }
    }

    /** Atomically reports provider-measured bytes; server caps the grant and marks it completed. */
    fun recordTrafficUsage(session: AuthSession, requestId: String, bytes: Long): Long {
        require(bytes in 1..64L * 1024L * 1024L) { "Usage report must be between 1 byte and 64 MiB." }
        val rows = JSONArray(call("/rest/v1/rpc/record_netswap_usage", "POST", session.accessToken,
            JSONObject().put("p_request_id", requestId).put("p_bytes", bytes)))
        require(rows.length() == 1) { "Server did not confirm the usage report." }
        return rows.getJSONObject(0).getLong("used_bytes")
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
