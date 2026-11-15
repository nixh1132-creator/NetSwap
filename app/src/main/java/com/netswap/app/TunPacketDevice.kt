package com.netswap.app

import java.io.Closeable

/** One complete IP packet per read/write operation on an Android TUN descriptor. */
interface TunPacketDevice : Closeable {
    /** Returns packet byte count, or -1 when the device reaches EOF. */
    fun readPacket(buffer: ByteArray): Int

    /** Writes exactly one complete IP packet. */
    fun writePacket(packet: ByteArray)
}
