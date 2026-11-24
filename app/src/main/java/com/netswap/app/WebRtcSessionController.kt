package com.netswap.app

import android.content.Context
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnection.PeerConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Owns signaling, WebRTC data transport, stream framing, and the attached
 * receiver SOCKS / provider TCP+UDP endpoints for one approved request.
 */
class WebRtcSessionController(
    context: Context,
    private val session: AuthSession,
    private val request: ShareRequest,
    private val onState: (PeerConnectionState) -> Unit,
    private val onMessage: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var activeSession: AuthSession = session
    private val sessionRefreshLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Retry authenticated signaling once after refreshing an expired/rejected access token. */
    private fun <T> withFreshSession(block: (AuthSession) -> T): T {
        val current = activeSession
        try {
            return block(current)
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            val authRejected = message.contains("401") ||
                message.contains("JWT", ignoreCase = true) ||
                message.contains("PGRST303", ignoreCase = true)
            if (!authRejected) throw failure
            val refreshed = synchronized(sessionRefreshLock) {
                if (activeSession.accessToken == current.accessToken) {
                    val next = NetSwapApi.refresh(current.refreshToken)
                    activeSession = next
                    appContext.getSharedPreferences("netswap_session", Context.MODE_PRIVATE)
                        .edit()
                        .putString("access_token", next.accessToken)
                        .putString("refresh_token", next.refreshToken)
                        .apply()
                }
                activeSession
            }
            return block(refreshed)
        }
    }
    private val closed = AtomicBoolean(false)
    private val seenSignals = Collections.synchronizedSet(mutableSetOf<String>())
    private val offerHandled = AtomicBoolean(false)
    private val answerHandled = AtomicBoolean(false)
    @Volatile private var negotiationId: String? = null
    private val peerUserId = if (session.userId == request.senderId) request.receiverId else request.senderId
    private val isOfferer = session.userId == request.senderId
    private var link: WebRtcPeerLink? = null
    private var streamBridge: PeerStreamTransportBridge? = null
    private var factory: PeerConnectionFactory? = null
    private var pollJob: Job? = null
    @Volatile private var socketEndpoint: PeerStreamSocketEndpoint? = null
    @Volatile private var datagramEndpoint: PeerStreamDatagramEndpoint? = null
    @Volatile private var receiverProxy: ReceiverSocks5Proxy? = null

    fun start() {
        check(!closed.get()) { "Peer session is closed." }
        if (link != null) return
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
            val peerFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = peerFactory
            val iceJson = withFreshSession { fresh -> NetSwapApi.peerIceServers(fresh) }
            val ice = buildList {
                for (index in 0 until iceJson.length()) {
                    val item = iceJson.getJSONObject(index)
                    val username = item.optString("username")
                    val credential = item.optString("credential")
                    // TURN providers may return one URL as a string or several as an array.
                    // Accept both shapes; otherwise a valid TURN response can be silently discarded.
                    val rawUrls = item.opt("urls")
                    val urls = when (rawUrls) {
                        is String -> listOf(rawUrls)
                        is org.json.JSONArray -> (0 until rawUrls.length()).map { rawUrls.optString(it) }
                        else -> emptyList()
                    }
                    for (url in urls) {
                        if (url.isBlank()) continue
                        val builder = PeerConnection.IceServer.builder(url)
                        if (username.isNotBlank() && credential.isNotBlank()) {
                            builder.setUsername(username).setPassword(credential)
                        }
                        add(builder.createIceServer())
                    }
                }
            }
            check(ice.isNotEmpty()) { "TURN/ICE server list is empty." }
            var incomingBridge: PeerStreamTransportBridge? = null
            val peerLink = WebRtcPeerLink(
                factory = peerFactory,
                iceServers = ice,
                onSignal = { type, payload ->
                    // Tag every SDP/ICE packet so retries cannot mix old candidates.
                    negotiationId?.let { payload.put("negotiation_id", it) }
                    scope.launch {
                        runCatching {
                            withFreshSession { fresh -> NetSwapApi.sendPeerSignal(fresh, request.id, peerUserId, type, payload) }
                        }.onFailure { onError(it.message ?: "Could not send peer signal.") }
                    }
                },
                onState = onState,
                onMessage = { bytes ->
                    incomingBridge?.receive(bytes)
                    onMessage(bytes)
                },
                onError = onError,
                createDataChannel = isOfferer
            )
            link = peerLink
            val bridge = PeerStreamTransportBridge(
                sendBinary = { bytes -> peerLink.send(bytes) },
                onOpen = { streamId, protocol, destination ->
                    when (protocol) {
                        PeerStreamRouter.Protocol.TCP -> socketEndpoint?.openTcp(streamId, destination) == true
                        PeerStreamRouter.Protocol.UDP -> datagramEndpoint?.openUdp(streamId, destination) == true
                    }
                },
                onData = { streamId, protocol, payload ->
                    when (protocol) {
                        PeerStreamRouter.Protocol.TCP -> {
                            val receiver = receiverProxy
                            if (receiver != null) receiver.onPeerData(streamId, payload)
                            else if (socketEndpoint?.writeTcp(streamId, payload) != true) bridgeError(streamId, "TCP stream write failed.")
                        }
                        PeerStreamRouter.Protocol.UDP -> {
                            val receiver = receiverProxy
                            if (receiver != null) receiver.onPeerUdpData(streamId, payload)
                            else if (datagramEndpoint?.sendUdp(streamId, payload) != true) bridgeError(streamId, "UDP datagram send failed.")
                        }
                    }
                },
                onClose = { streamId ->
                    socketEndpoint?.closeStream(streamId)
                    datagramEndpoint?.closeStream(streamId)
                    receiverProxy?.onPeerError(streamId)
                }
            )
            incomingBridge = bridge
            streamBridge = bridge
            if (isOfferer) {
                negotiationId = UUID.randomUUID().toString()
                peerLink.createOffer().whenComplete { _, error ->
                    if (error != null) onError(error.message ?: "Could not create peer offer.")
                }
            }
            pollJob = scope.launch {
                while (isActive && !closed.get()) {
                    try {
                        val incoming = withFreshSession { fresh -> NetSwapApi.pendingPeerSignals(fresh, request.id) }
                        incoming.forEach { signal ->
                            if (signal.fromUserId != peerUserId || !seenSignals.add(signal.id)) return@forEach
                            val signalNegotiationId = signal.payload.optString("negotiation_id")
                            when (signal.type) {
                                "offer" -> if (!isOfferer && signalNegotiationId.isNotBlank() && offerHandled.compareAndSet(false, true)) {
                                    negotiationId = signalNegotiationId
                                    val sdp = signal.payload.optString("sdp")
                                    if (sdp.isNotBlank()) peerLink.acceptOffer(sdp).whenComplete { _, error ->
                                        if (error != null) onError(error.message ?: "Could not accept peer offer.")
                                    }
                                }
                                "answer" -> if (isOfferer && signalNegotiationId.isNotBlank() && signalNegotiationId == negotiationId && answerHandled.compareAndSet(false, true)) {
                                    val sdp = signal.payload.optString("sdp")
                                    if (sdp.isNotBlank()) peerLink.acceptAnswer(sdp).whenComplete { _, error ->
                                        if (error != null) onError(error.message ?: "Could not accept peer answer.")
                                    }
                                }
                                "ice" -> if (signalNegotiationId.isNotBlank() && signalNegotiationId == negotiationId) peerLink.addIceCandidate(signal.payload)
                                "hangup" -> close()
                            }
                        }
                        if (seenSignals.size > 500) seenSignals.clear()
                    } catch (error: Exception) {
                        onError(error.message ?: "Peer signaling check failed.")
                    }
                    delay(1200)
                }
            }
        } catch (error: Exception) {
            onError(error.message ?: "Could not initialize WebRTC.")
            close()
        }
    }

    fun isDataChannelOpen(): Boolean = link?.isDataChannelOpen() == true

    fun hasTurnRelayCandidate(): Boolean = link?.hasTurnRelayCandidate() == true

    fun isIceGatheringComplete(): Boolean = link?.isIceGatheringComplete() == true

    fun send(bytes: ByteArray): Boolean = link?.send(bytes) == true

    /** Attach the sender-side protected TCP endpoint before accepting peer streams. */
    fun attachSocketEndpoint(endpoint: PeerStreamSocketEndpoint) {
        check(!closed.get()) { "Peer session is closed." }
        socketEndpoint?.close()
        socketEndpoint = endpoint
    }

    /** Attach the sender-side protected UDP endpoint before accepting UDP streams. */
    fun attachDatagramEndpoint(endpoint: PeerStreamDatagramEndpoint) {
        check(!closed.get()) { "Peer session is closed." }
        datagramEndpoint?.close()
        datagramEndpoint = endpoint
    }

    /** Attach the receiver's loopback SOCKS adapter after the peer data channel is ready. */
    fun attachReceiverProxy(proxy: ReceiverSocks5Proxy) {
        check(!closed.get()) { "Peer session is closed." }
        receiverProxy?.close()
        receiverProxy = proxy
    }

    private fun bridgeError(streamId: Int, message: String) {
        streamBridge?.sendError(streamId, message)
        onError(message)
    }

    /** Framed stream APIs are available for the upcoming socket/TUN integration. */
    fun openTcp(streamId: Int, destination: ByteArray): Boolean =
        streamBridge?.openTcp(streamId, destination) == true

    fun openUdp(streamId: Int, destination: ByteArray): Boolean =
        streamBridge?.openUdp(streamId, destination) == true

    fun sendTcpData(streamId: Int, payload: ByteArray): Boolean =
        streamBridge?.sendTcpData(streamId, payload) == true

    fun sendUdpData(streamId: Int, payload: ByteArray): Boolean =
        streamBridge?.sendUdpData(streamId, payload) == true

    fun closeStream(streamId: Int): Boolean =
        streamBridge?.closeStream(streamId) == true

    fun sendPing(): Boolean = streamBridge?.sendPing() == true

    fun sendStreamError(streamId: Int, message: String): Boolean =
        streamBridge?.sendError(streamId, message) == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollJob?.cancel()
        streamBridge?.close()
        streamBridge = null
        socketEndpoint?.close()
        socketEndpoint = null
        datagramEndpoint?.close()
        datagramEndpoint = null
        receiverProxy?.close()
        receiverProxy = null
        link?.close()
        link = null
        factory?.dispose()
        factory = null
        scope.cancel()
    }
}
