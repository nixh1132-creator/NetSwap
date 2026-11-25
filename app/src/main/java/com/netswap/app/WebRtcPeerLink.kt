package com.netswap.app

import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebRTC DataChannel transport for the P2P leg.
 *
 * This is a transport primitive only: callers must supply authenticated ICE servers,
 * exchange SDP/ICE through NetSwapApi, and frame/multiplex proxy streams above it.
 * It deliberately does not claim to route Android VPN/TUN traffic by itself.
 */
class WebRtcPeerLink(
    private val factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    private val onSignal: (type: String, payload: JSONObject) -> Unit,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onMessage: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val createDataChannel: Boolean = true,
    private val onIceRoute: (String) -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val dataChannelReady = AtomicBoolean(false)
    private val turnRelayCandidateFound = AtomicBoolean(false)
    private val hostCandidateCount = AtomicInteger(0)
    private val srflxCandidateCount = AtomicInteger(0)
    private val relayCandidateCount = AtomicInteger(0)
    private val iceGatheringComplete = AtomicBoolean(false)
    private val config = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        // Allow WebRTC to nominate the best working path. Forcing RELAY can
        // make both phones fail when a TURN provider returns candidates but its
        // relay transport is unreachable on a carrier network. TURN remains in
        // the ICE server list and will be used when direct candidates cannot work.
    }
    private var channel: DataChannel? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionReady = false
    private val peer: PeerConnection

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                onError("ICE transport failed. Candidates gathered: host=${hostCandidateCount.get()}, srflx=${srflxCandidateCount.get()}, relay=${relayCandidateCount.get()}. Check TURN credentials, UDP/TCP reachability, and peer signaling.")
            }
        }
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            onState(state)
            if (state == PeerConnection.PeerConnectionState.CONNECTED) reportSelectedIceRoute()
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.set(true)
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            when {
                candidate.sdp.contains(" typ relay", ignoreCase = true) -> {
                    turnRelayCandidateFound.set(true)
                    relayCandidateCount.incrementAndGet()
                }
                candidate.sdp.contains(" typ srflx", ignoreCase = true) -> srflxCandidateCount.incrementAndGet()
                candidate.sdp.contains(" typ host", ignoreCase = true) -> hostCandidateCount.incrementAndGet()
            }
            onSignal("ice", JSONObject()
                .put("sdp", candidate.sdp)
                .put("sdp_mid", candidate.sdpMid)
                .put("sdp_mline_index", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(dataChannel: DataChannel) { attachChannel(dataChannel) }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {}
    }

    init {
        peer = requireNotNull(factory.createPeerConnection(config, observer)) {
            "Unable to create WebRTC peer connection."
        }
        if (createDataChannel) {
            val init = DataChannel.Init().apply { ordered = true }
            attachChannel(requireNotNull(peer.createDataChannel("netswap-data", init)) {
                "Unable to create WebRTC data channel."
            })
        }
    }

    /** Caller invokes on the offerer. */
    fun createOffer(): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        if (closed.get()) return failed("Peer link is closed.")
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) { result.completeExceptionally(IllegalStateException("Empty SDP offer.")); return }
                peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        result.complete(desc.description)
                        onSignal("offer", JSONObject().put("sdp", desc.description).put("sdp_type", "offer"))
                    }
                    override fun onSetFailure(error: String?) = fail(result, error)
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) = fail(result, error)
            override fun onSetFailure(error: String?) = fail(result, error)
        }, offerConstraints())
        return result
    }

    /** Caller invokes on the answerer after receiving the remote offer. */
    fun acceptOffer(sdp: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        if (closed.get()) return failed("Peer link is closed.")
        if (peer.signalingState() != PeerConnection.SignalingState.STABLE) return failed("Peer is already negotiating; stop both tunnels and start again.")
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                markRemoteDescriptionReady()
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) { result.completeExceptionally(IllegalStateException("Empty SDP answer.")); return }
                        peer.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                result.complete(desc.description)
                                onSignal("answer", JSONObject().put("sdp", desc.description).put("sdp_type", "answer"))
                            }
                            override fun onSetFailure(error: String?) = fail(result, error)
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) = fail(result, error)
                    override fun onSetFailure(error: String?) = fail(result, error)
                }, offerConstraints())
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) = fail(result, error)
        }, offer)
        return result
    }

    fun acceptAnswer(sdp: String): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        if (closed.get()) return failed("Peer link is closed.")
        if (peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) { result.complete(Unit); return result }
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                markRemoteDescriptionReady()
                result.complete(Unit)
            }
            override fun onSetFailure(error: String?) = fail(result, error)
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        return result
    }

    fun addIceCandidate(payload: JSONObject): Boolean {
        val sdp = payload.optString("sdp")
        if (sdp.isBlank()) return false
        val mid = payload.optString("sdp_mid").ifBlank { null }
        val line = payload.optInt("sdp_mline_index", 0)
        val candidate = IceCandidate(mid, line, sdp)
        synchronized(pendingIceCandidates) {
            if (!remoteDescriptionReady) {
                // Supabase polling can deliver ICE before the SDP offer/answer.
                // Queue it until setRemoteDescription succeeds instead of dropping it.
                if (pendingIceCandidates.size >= MAX_PENDING_ICE_CANDIDATES) return false
                pendingIceCandidates.add(candidate)
                return true
            }
        }
        return peer.addIceCandidate(candidate)
    }

    private fun markRemoteDescriptionReady() {
        val queued = synchronized(pendingIceCandidates) {
            remoteDescriptionReady = true
            pendingIceCandidates.toList().also { pendingIceCandidates.clear() }
        }
        queued.forEach { candidate ->
            if (!peer.addIceCandidate(candidate)) {
                onError("A queued WebRTC network candidate could not be added.")
            }
        }
    }


    /**
     * Reports the nominated ICE pair from WebRTC stats. Candidate gathering alone
     * is not proof that TURN/direct connectivity was selected or is carrying bytes.
     */
    private fun reportSelectedIceRoute() {
        if (closed.get()) return
        peer.getStats { report: RTCStatsReport ->
            if (closed.get()) return@getStats
            val stats = report.statsMap
            val transport = stats.values.firstOrNull { it.type == "transport" }
            val selectedPairId = transport?.members?.get("selectedCandidatePairId") as? String
            val pair = selectedPairId?.let { stats[it] }
                ?: stats.values.firstOrNull { stat ->
                    stat.type == "candidate-pair" &&
                        stat.members["state"] == "succeeded" &&
                        stat.members["nominated"] == true
                }
            if (pair == null) {
                onIceRoute("Connected, but selected ICE candidate pair is not exposed by this WebRTC build.")
                return@getStats
            }
            val localId = pair.members["localCandidateId"] as? String
            val remoteId = pair.members["remoteCandidateId"] as? String
            val local = localId?.let { stats[it] }
            val remote = remoteId?.let { stats[it] }
            fun member(stat: org.webrtc.RTCStats?, key: String): String =
                stat?.members?.get(key)?.toString() ?: "unknown"
            val localType = member(local, "candidateType")
            val remoteType = member(remote, "candidateType")
            val protocol = member(local, "protocol")
            val bytesSent = member(pair, "bytesSent")
            val bytesReceived = member(pair, "bytesReceived")
            val rtt = member(pair, "currentRoundTripTime")
            val route = if (localType == "relay" || remoteType == "relay") "TURN relay" else "direct"
            onIceRoute("$route selected; local=$localType, remote=$remoteType, protocol=$protocol, bytesSent=$bytesSent, bytesReceived=$bytesReceived, rttSeconds=$rtt")
        }
    }

    fun isDataChannelOpen(): Boolean = dataChannelReady.get() && channel?.state() == DataChannel.State.OPEN

    fun hasTurnRelayCandidate(): Boolean = turnRelayCandidateFound.get()

    fun isIceGatheringComplete(): Boolean = iceGatheringComplete.get()

    /** Binary messages are bounded to avoid unbounded memory pressure. */
    fun send(bytes: ByteArray): Boolean {
        if (closed.get() || !dataChannelReady.get() || bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return false
        val buffer = ByteBuffer.wrap(bytes)
        return channel?.send(DataChannel.Buffer(buffer, true)) == true
    }

    private fun attachChannel(value: DataChannel) {
        channel?.unregisterObserver()
        channel = value
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                dataChannelReady.set(value.state() == DataChannel.State.OPEN)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary && buffer.data.remaining() <= MAX_MESSAGE_BYTES) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    onMessage(bytes)
                }
            }
        })
        dataChannelReady.set(value.state() == DataChannel.State.OPEN)
    }

    private fun offerConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun <T> fail(future: CompletableFuture<T>, error: String?) {
        future.completeExceptionally(IllegalStateException(error ?: "WebRTC operation failed."))
        onError(error ?: "WebRTC operation failed.")
    }

    private fun <T> failed(message: String): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(IllegalStateException(message)) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataChannelReady.set(false)
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null
        peer.close()
        peer.dispose()
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024
        private const val MAX_PENDING_ICE_CANDIDATES = 256
    }
}
