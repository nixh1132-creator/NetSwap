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

/**
 * Owns the authenticated signaling lifecycle for one approved sharing request.
 * This establishes a WebRTC data channel only; it does not yet forward VPN packets.
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val seenSignals = Collections.synchronizedSet(mutableSetOf<String>())
    private val peerUserId = if (session.userId == request.senderId) request.receiverId else request.senderId
    private val isOfferer = session.userId == request.senderId
    private var link: WebRtcPeerLink? = null
    private var streamBridge: PeerStreamTransportBridge? = null
    private var factory: PeerConnectionFactory? = null
    private var pollJob: Job? = null

    fun start() {
        check(!closed.get()) { "Peer session is closed." }
        if (link != null) return
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
            val peerFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = peerFactory
            val ice = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            var incomingBridge: PeerStreamTransportBridge? = null
            val peerLink = WebRtcPeerLink(
                factory = peerFactory,
                iceServers = ice,
                onSignal = { type, payload ->
                    scope.launch {
                        runCatching {
                            NetSwapApi.sendPeerSignal(session, request.id, peerUserId, type, payload)
                        }.onFailure { onError(it.message ?: "Could not send peer signal.") }
                    }
                },
                onState = onState,
                onMessage = { bytes ->
                    incomingBridge?.receive(bytes)
                    onMessage(bytes)
                },
                onError = onError
            )
            link = peerLink
            val bridge = PeerStreamTransportBridge(
                sendBinary = { bytes -> peerLink.send(bytes) },
                onOpen = { streamId, protocol, destination ->
                    onError("Peer stream $streamId requested; socket forwarding is not connected yet.")
                    false
                },
                onData = { _, _, _ -> },
                onClose = { }
            )
            incomingBridge = bridge
            streamBridge = bridge
            if (isOfferer) {
                peerLink.createOffer().whenComplete { _, error ->
                    if (error != null) onError(error.message ?: "Could not create peer offer.")
                }
            }
            pollJob = scope.launch {
                while (isActive && !closed.get()) {
                    try {
                        val incoming = NetSwapApi.pendingPeerSignals(session, request.id)
                        incoming.forEach { signal ->
                            if (signal.fromUserId != peerUserId || !seenSignals.add(signal.id)) return@forEach
                            when (signal.type) {
                                "offer" -> if (!isOfferer) {
                                    val sdp = signal.payload.optString("sdp")
                                    if (sdp.isNotBlank()) peerLink.acceptOffer(sdp).whenComplete { _, error ->
                                        if (error != null) onError(error.message ?: "Could not accept peer offer.")
                                    }
                                }
                                "answer" -> if (isOfferer) {
                                    val sdp = signal.payload.optString("sdp")
                                    if (sdp.isNotBlank()) peerLink.acceptAnswer(sdp).whenComplete { _, error ->
                                        if (error != null) onError(error.message ?: "Could not accept peer answer.")
                                    }
                                }
                                "ice" -> peerLink.addIceCandidate(signal.payload)
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

    fun send(bytes: ByteArray): Boolean = link?.send(bytes) == true

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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollJob?.cancel()
        streamBridge?.close()
        streamBridge = null
        link?.close()
        link = null
        factory?.dispose()
        factory = null
        scope.cancel()
    }
}
