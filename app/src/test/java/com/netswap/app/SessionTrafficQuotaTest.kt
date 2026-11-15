package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class SessionTrafficQuotaTest {
    @Test fun countsBothDirectionsAgainstOneSharedQuota() {
        val quota = SessionTrafficQuota(100)
        assertTrue(quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, 40))
        assertTrue(quota.record(TrafficDirection.SENT_TO_RECEIVER, 35))
        val state = quota.snapshot()
        assertEquals(40L, state.receivedFromReceiverBytes)
        assertEquals(35L, state.sentToReceiverBytes)
        assertEquals(25L, state.remainingBytes)
        assertTrue(state.active)
    }

    @Test fun exactQuotaExhaustionStopsFurtherTraffic() {
        val quota = SessionTrafficQuota(64)
        assertTrue(quota.record(TrafficDirection.SENT_TO_RECEIVER, 64))
        assertFalse(quota.snapshot().active)
        assertFalse(quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, 1))
        assertEquals(64L, quota.snapshot().totalBytes)
    }

    @Test fun packetThatWouldExceedQuotaIsRejectedWithoutPartialAccounting() {
        val quota = SessionTrafficQuota(50)
        assertTrue(quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, 45))
        assertFalse(quota.record(TrafficDirection.SENT_TO_RECEIVER, 6))
        val state = quota.snapshot()
        assertEquals(45L, state.totalBytes)
        assertEquals(5L, state.remainingBytes)
        assertFalse(state.active)
    }

    @Test fun revokedSessionRejectsAllFurtherTraffic() {
        val quota = SessionTrafficQuota(100)
        assertTrue(quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, 10))
        quota.revoke()
        assertFalse(quota.record(TrafficDirection.SENT_TO_RECEIVER, 1))
        assertEquals(10L, quota.snapshot().totalBytes)
    }

    @Test fun concurrentRecordsNeverExceedSharedQuota() {
        val quota = SessionTrafficQuota(50)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(10)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(100)
        repeat(100) { index ->
            workers.execute {
                try {
                    start.await()
                    val direction = if (index % 2 == 0) TrafficDirection.RECEIVED_FROM_RECEIVER
                    else TrafficDirection.SENT_TO_RECEIVER
                    if (quota.record(direction, 1)) accepted.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        workers.shutdownNow()
        assertEquals(50, accepted.get())
        assertEquals(50L, quota.snapshot().totalBytes)
        assertFalse(quota.snapshot().active)
    }

    @Test fun rejectsInvalidQuotaAndPacketSizes() {
        assertThrows(IllegalArgumentException::class.java) { SessionTrafficQuota(0) }
        val quota = SessionTrafficQuota(100)
        assertThrows(IllegalArgumentException::class.java) {
            quota.record(TrafficDirection.SENT_TO_RECEIVER, 0)
        }
    }
}
