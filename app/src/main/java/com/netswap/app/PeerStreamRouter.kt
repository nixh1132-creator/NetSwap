package com.netswap.app

/**
 * Validates stream lifecycle for frames received over a peer data channel.
 *
 * This is deliberately transport-agnostic: callers provide callbacks that connect
 * accepted streams to the sender's protected sockets. It does not open network sockets.
 */
class PeerStreamRouter(
    private val onOpen: (streamId: Int, protocol: Protocol, destination: ByteArray) -> Boolean,
    private val onData: (streamId: Int, protocol: Protocol, payload: ByteArray) -> Unit,
    private val onClose: (streamId: Int) -> Unit,
    private val onControl: (frame: PeerStreamFrame) -> Unit,
    private val maxStreams: Int = DEFAULT_MAX_STREAMS
) {
    enum class Protocol { TCP, UDP }

    private val streams = LinkedHashMap<Int, Protocol>()
    private var closed = false

    init {
        require(maxStreams in 1..MAX_ALLOWED_STREAMS) { "Invalid peer stream limit." }
    }

    @Synchronized
    fun accept(frame: PeerStreamFrame): Boolean {
        if (closed) return false
        return when (frame.type) {
            PeerStreamFrame.Type.OPEN_TCP -> open(frame, Protocol.TCP)
            PeerStreamFrame.Type.OPEN_UDP -> open(frame, Protocol.UDP)
            PeerStreamFrame.Type.DATA -> data(frame, Protocol.TCP)
            PeerStreamFrame.Type.UDP_DATA -> data(frame, Protocol.UDP)
            PeerStreamFrame.Type.CLOSE -> closeStream(frame.streamId)
            PeerStreamFrame.Type.ERROR -> closeStream(frame.streamId)
            PeerStreamFrame.Type.PING, PeerStreamFrame.Type.PONG -> {
                onControl(frame)
                true
            }
        }
    }

    @Synchronized
    fun closeAll() {
        if (closed) return
        closed = true
        val ids = streams.keys.toList()
        streams.clear()
        ids.forEach(onClose)
    }

    @Synchronized
    fun activeStreamCount(): Int = streams.size

    private fun open(frame: PeerStreamFrame, protocol: Protocol): Boolean {
        if (streams.containsKey(frame.streamId) || streams.size >= maxStreams) return false
        // Empty destinations are invalid; caller validates and interprets its own
        // bounded destination encoding before opening a protected upstream socket.
        if (frame.payload.isEmpty()) return false
        if (!onOpen(frame.streamId, protocol, frame.payload.copyOf())) return false
        streams[frame.streamId] = protocol
        return true
    }

    private fun data(frame: PeerStreamFrame, protocol: Protocol): Boolean {
        if (streams[frame.streamId] != protocol) return false
        onData(frame.streamId, protocol, frame.payload.copyOf())
        return true
    }

    private fun closeStream(streamId: Int): Boolean {
        if (streams.remove(streamId) == null) return false
        onClose(streamId)
        return true
    }

    companion object {
        const val DEFAULT_MAX_STREAMS = 128
        const val MAX_ALLOWED_STREAMS = 256
    }
}
