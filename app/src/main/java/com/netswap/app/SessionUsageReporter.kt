package com.netswap.app

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Periodically persists provider-side byte deltas. Local quota remains the immediate per-packet gate. */
class SessionUsageReporter(
    private val quota: SessionTrafficQuota,
    private val reportUsage: (bytes: Long) -> Long,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "netswap-usage-sync").apply { isDaemon = true }
    }
    @Volatile private var reportedBytes = 0L

    init { require(intervalMillis >= 250L) }

    fun start(): SessionUsageReporter {
        check(!closed.get()) { "Usage reporter is closed." }
        scheduler.scheduleWithFixedDelay({
            try { flushNow() } catch (_: Exception) { /* Keep delta pending and retry next interval. */ }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        return this
    }

    @Synchronized internal fun flushNow(): Long {
        check(!closed.get()) { "Usage reporter is closed." }
        flushPending()
        return reportedBytes
    }

    internal fun reportedBytes(): Long = reportedBytes

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { flushPending() } catch (_: Exception) { /* Offline final delta may be lost on process exit. */ }
        scheduler.shutdownNow()
    }

    @Synchronized private fun flushPending() {
        val target = quota.snapshot().totalBytes
        while (reportedBytes < target) {
            val batch = minOf(target - reportedBytes, MAX_REPORT_BYTES)
            val serverUsed = reportUsage(batch)
            reportedBytes += batch
            if (serverUsed >= quota.snapshot().quotaBytes) quota.revoke()
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val MAX_REPORT_BYTES = 64L * 1024L * 1024L
    }
}