package com.netswap.app

/**
 * Thread-safe, fail-closed per-session byte budget.
 *
 * This is an accounting primitive for the future packet-forwarding path. It is not
 * connected to sockets/TUN yet; callers must invoke record() for every forwarded
 * packet before forwarding it and stop immediately when it returns false.
 */
enum class TrafficDirection { RECEIVED_FROM_RECEIVER, SENT_TO_RECEIVER }

data class TrafficQuotaSnapshot(
    val quotaBytes: Long,
    val receivedFromReceiverBytes: Long,
    val sentToReceiverBytes: Long,
    val remainingBytes: Long,
    val active: Boolean
) {
    val totalBytes: Long
        get() = receivedFromReceiverBytes + sentToReceiverBytes
}

class SessionTrafficQuota(quotaBytes: Long) {
    private val limit = quotaBytes.also { require(it > 0L) { "Quota must be greater than zero." } }
    private var received = 0L
    private var sent = 0L
    private var active = true

    /**
     * Reserves packet bytes against the shared bidirectional quota.
     * Returns false without changing counters if inactive or the packet would exceed quota.
     */
    @Synchronized
    fun record(direction: TrafficDirection, packetBytes: Long): Boolean {
        require(packetBytes > 0L) { "Packet size must be greater than zero." }
        if (!active) return false
        val total = received + sent
        if (packetBytes > limit - total) {
            active = false
            return false
        }
        when (direction) {
            TrafficDirection.RECEIVED_FROM_RECEIVER -> received += packetBytes
            TrafficDirection.SENT_TO_RECEIVER -> sent += packetBytes
        }
        if (received + sent == limit) active = false
        return true
    }

    @Synchronized
    fun revoke() {
        active = false
    }

    @Synchronized
    fun snapshot(): TrafficQuotaSnapshot {
        val total = received + sent
        return TrafficQuotaSnapshot(
            quotaBytes = limit,
            receivedFromReceiverBytes = received,
            sentToReceiverBytes = sent,
            remainingBytes = limit - total,
            active = active
        )
    }
}
