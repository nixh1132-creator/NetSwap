package com.netswap.app

import org.junit.Assert.*
import org.junit.Test

class SessionUsageReporterTest {
    @Test fun flushReportsOnlyNewBytes() {
        val quota = SessionTrafficQuota(1_000)
        val batches = mutableListOf<Long>()
        val reporter = SessionUsageReporter(quota, { bytes -> batches.add(bytes); batches.sum() })
        quota.record(TrafficDirection.SENT_TO_RECEIVER, 100)
        assertEquals(100L, reporter.flushNow())
        assertEquals(100L, reporter.flushNow())
        quota.record(TrafficDirection.RECEIVED_FROM_RECEIVER, 50)
        assertEquals(150L, reporter.flushNow())
        assertEquals(listOf(100L, 50L), batches)
        reporter.close()
    }

    @Test fun failedReportKeepsBytesPendingForRetry() {
        val quota = SessionTrafficQuota(1_000)
        var shouldFail = true
        val batches = mutableListOf<Long>()
        val reporter = SessionUsageReporter(quota, { bytes ->
            if (shouldFail) error("temporary network failure")
            batches.add(bytes); batches.sum()
        })
        quota.record(TrafficDirection.SENT_TO_RECEIVER, 80)
        try { reporter.flushNow(); fail("Expected report failure") } catch (_: IllegalStateException) {}
        assertEquals(0L, reporter.reportedBytes())
        shouldFail = false
        assertEquals(80L, reporter.flushNow())
        assertEquals(listOf(80L), batches)
        reporter.close()
    }

    @Test fun largeDeltasAreSplitIntoRpcSafeBatches() {
        val quota = SessionTrafficQuota(150L * 1024L * 1024L)
        val batches = mutableListOf<Long>()
        val reporter = SessionUsageReporter(quota, { bytes -> batches.add(bytes); batches.sum() })
        quota.record(TrafficDirection.SENT_TO_RECEIVER, 130L * 1024L * 1024L)
        reporter.flushNow()
        assertEquals(3, batches.size)
        assertEquals(SessionUsageReporter.MAX_REPORT_BYTES, batches[0])
        assertEquals(SessionUsageReporter.MAX_REPORT_BYTES, batches[1])
        assertEquals(2L * 1024L * 1024L, batches[2])
        reporter.close()
    }

    @Test fun serverQuotaCompletionRevokesLocalBudget() {
        val quota = SessionTrafficQuota(100)
        val reporter = SessionUsageReporter(quota) { 100L }
        quota.record(TrafficDirection.SENT_TO_RECEIVER, 100)
        reporter.flushNow()
        assertFalse(quota.snapshot().active)
        reporter.close()
    }
}