package com.netswap.app

/**
 * Connects the bounded NSWP frame protocol to a binary peer transport such as
 * WebRtcPeerLink. Socket creation and VPN/TUN integration remain caller-owned.
 */
class PeerStreamTransportBridge(
    private val sendBinary: (ByteArray) -> Boolean,
    onOpen: (streamId: Int, protocol: PeerStreamRouter.Protocol, destination: ByteArray) -> Boolean,
    onData: (streamId: Int, protocol: PeerStreamRouter.Protocol, payload: ByteArray) -> Unit,
    onClose: (streamId: Int) -> Unit,
    private val maxStreams: Int = PeerStreamRouter.DEFAULT_MAX_STREAMS
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val router = PeerStreamRouter(
        onOpen = onOpen,
        onData = onData,
        onClose = onClose,
        onControl = { frame ->
            if (frame.type == PeerStreamFrame.Type.PING) {
                sendFrame(PeerStreamFrame(PeerStreamFrame.Type.PONG, 0))
            }
        },
        maxStreams = maxStreams
    )

    /** Feed each complete binary message received from the peer data channel. */
    fun receive(bytes: ByteArray): Boolean {
        if (closed.get() || bytes.isEmpty() || bytes.size > PeerStreamFrame.MAX_FRAME_BYTES) return false
        val frame = try {
            PeerStreamFrame.decode(bytes)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            router.accept(frame)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun openTcp(streamId: Int, destination: ByteArray): Boolean =
        sendFrame(PeerStreamFrame(PeerStreamFrame.Type.OPEN_TCP, streamId, destination))

    fun openUdp(streamId: Int, destination: ByteArray): Boolean =
        sendFrame(PeerStreamFrame(PeerStreamFrame.Type.OPEN_UDP, streamId, destination))

    fun sendTcpData(streamId: Int, payload: ByteArray): Boolean =
        sendFrame(PeerStreamFrame(PeerStreamFrame.Type.DATA, streamId, payload))

    fun sendUdpData(streamId: Int, payload: ByteArray): Boolean =
        sendFrame(PeerStreamFrame(PeerStreamFrame.Type.UDP_DATA, streamId, payload))

    fun closeStream(streamId: Int): Boolean =
        sendFrame(PeerStreamFrame(PeerStreamFrame.Type.CLOSE, streamId))

    fun sendPing(): Boolean = sendFrame(PeerStreamFrame(PeerStreamFrame.Type.PING, 0))

    fun activeStreamCount(): Int = router.activeStreamCount()

    private fun sendFrame(frame: PeerStreamFrame): Boolean {
        if (closed.get()) return false
        return try {
            sendBinary(frame.encode())
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        router.closeAll()
    }
}
