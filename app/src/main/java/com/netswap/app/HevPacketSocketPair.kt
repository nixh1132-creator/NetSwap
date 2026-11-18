package com.netswap.app

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Packet-preserving AF_UNIX/SOCK_DGRAM pair for connecting Hev to NetSwap's
 * userspace packet pipeline without giving two readers the Android VPN TUN fd.
 *
 * Hev owns [hevDescriptor] while running. NetSwap owns [packetDevice].
 * Stop Hev before closing this pair. This primitive is not wired into a live
 * session until the native Hev start/stop wrapper and end-to-end tests exist.
 */
class HevPacketSocketPair private constructor(
    val hevDescriptor: ParcelFileDescriptor,
    val packetDevice: TunPacketDevice
) : Closeable {
    private val closed = AtomicBoolean(false)

    /**
     * Returns an independently-owned descriptor for Hev. Pass its fd to
     * TProxyStartService and close this duplicate only after TProxyStopService
     * has returned. The original descriptor remains owned by this pair.
     */
    @Synchronized
    fun duplicateHevDescriptor(): ParcelFileDescriptor {
        check(!closed.get()) { "Hev packet socket pair is closed" }
        return ParcelFileDescriptor.dup(hevDescriptor.fileDescriptor)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { packetDevice.close() } finally { hevDescriptor.close() }
    }

    private class DatagramPacketDevice(
        private val descriptor: ParcelFileDescriptor
    ) : TunPacketDevice {
        private val closed = AtomicBoolean(false)
        private val readLock = Any()
        private val writeLock = Any()

        override fun readPacket(buffer: ByteArray): Int {
            check(!closed.get()) { "Packet socket is closed" }
            require(buffer.isNotEmpty()) { "Packet buffer must not be empty" }
            return synchronized(readLock) {
                check(!closed.get()) { "Packet socket is closed" }
                try {
                    Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                } catch (failure: Exception) {
                    throw IOException("Packet socket read failed", failure)
                }
            }
        }

        override fun writePacket(packet: ByteArray) {
            check(!closed.get()) { "Packet socket is closed" }
            require(packet.isNotEmpty() && packet.size <= TunPacketPump.MAX_PACKET_SIZE) {
                "Invalid IP packet size"
            }
            synchronized(writeLock) {
                check(!closed.get()) { "Packet socket is closed" }
                try {
                    val written = Os.write(descriptor.fileDescriptor, packet, 0, packet.size)
                    check(written == packet.size) { "Packet datagram was only partially written" }
                } catch (failure: Exception) {
                    throw IOException("Packet socket write failed", failure)
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) descriptor.close()
        }
    }

    companion object {
        init { System.loadLibrary("netswap_jni") }

        /**
         * Creates a SOCK_DGRAM socketpair: each read receives exactly one packet.
         * The returned descriptors are close-on-exec and initially blocking.
         */
        fun create(): HevPacketSocketPair {
            val fds = nativeCreateDatagramPair()
            check(fds.size == 2 && fds[0] >= 0 && fds[1] >= 0) {
                "Native packet socket pair returned invalid descriptors"
            }
            val hev = ParcelFileDescriptor.adoptFd(fds[0])
            try {
                val bridge = ParcelFileDescriptor.adoptFd(fds[1])
                return HevPacketSocketPair(hev, DatagramPacketDevice(bridge))
            } catch (failure: Exception) {
                hev.close()
                throw failure
            }
        }

        private external fun nativeCreateDatagramPair(): IntArray
    }
}
