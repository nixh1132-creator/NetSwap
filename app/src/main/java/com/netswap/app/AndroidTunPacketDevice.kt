package com.netswap.app

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns duplicated descriptors for one Android VpnService TUN interface.
 *
 * Android's TUN fd is packet-oriented: each read yields one IP packet and each
 * write submits one packet. The caller retains ownership of the original
 * ParcelFileDescriptor; this adapter closes only its duplicated descriptors.
 */
class AndroidTunPacketDevice private constructor(
    private val inputDescriptor: ParcelFileDescriptor,
    private val outputDescriptor: ParcelFileDescriptor
) : TunPacketDevice {
    private val input = FileInputStream(inputDescriptor.fileDescriptor)
    private val output = FileOutputStream(outputDescriptor.fileDescriptor)
    private val closed = AtomicBoolean(false)
    private val readLock = Any()
    private val writeLock = Any()

    override fun readPacket(buffer: ByteArray): Int {
        check(!closed.get()) { "TUN device is closed" }
        require(buffer.isNotEmpty()) { "TUN read buffer must not be empty" }
        return synchronized(readLock) {
            check(!closed.get()) { "TUN device is closed" }
            input.read(buffer)
        }
    }

    override fun writePacket(packet: ByteArray) {
        check(!closed.get()) { "TUN device is closed" }
        require(packet.isNotEmpty() && packet.size <= TunPacketPump.MAX_PACKET_SIZE) {
            "Invalid TUN packet size"
        }
        synchronized(writeLock) {
            check(!closed.get()) { "TUN device is closed" }
            output.write(packet)
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Closing both duplicated descriptors unblocks a pending TUN read.
            try { input.close() } catch (_: Exception) { }
            try { output.close() } catch (_: Exception) { }
            try { inputDescriptor.close() } catch (_: Exception) { }
            try { outputDescriptor.close() } catch (_: Exception) { }
        }
    }

    companion object {
        /**
         * Duplicates a live TUN descriptor. The original descriptor remains
         * owned by the VpnService and may be closed independently by that service.
         */
        fun from(parcelFileDescriptor: ParcelFileDescriptor): AndroidTunPacketDevice {
            val inputCopy = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
            try {
                val outputCopy = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
                return AndroidTunPacketDevice(inputCopy, outputCopy)
            } catch (failure: Exception) {
                try { inputCopy.close() } catch (_: Exception) { }
                throw failure
            }
        }
    }
}
