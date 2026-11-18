package com.netswap.app

import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val dataChannelReady = AtomicBoolean(false)
    private val config = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
    private var channel: DataChannel? = null
    private val peer: PeerConnection

    init {
        peer = requireNotNull(factory.createPeerConnection(config, observer)) {
            "Unable to create WebRTC peer connection."
        }
        val init = DataChannel.Init().apply { ordered = true }
        attachChannel(requireNotNull(peer.createDataChannel("netswap-data", init)) { "Unable to create WebRTC data channel." })
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
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
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
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { result.complete(Unit) }
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
        return peer.addIceCandidate(IceCandidate(mid, line, sdp))
    }

    /** Binary messages are bounded to avoid unbounded memory pressure. */
    fun send(bytes: ByteArray): Boolean {
        if (closed.get() || !dataChannelReady.get() || bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return false
        val buffer = ByteBuffer.wrap(bytes)
        return channel?.send(DataChannel.Buffer(buffer, true)) == true
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) { onState(state) }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(candidate: IceCandidate) {
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
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null
        peer.close()
        peer.dispose()
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024
    }
}
