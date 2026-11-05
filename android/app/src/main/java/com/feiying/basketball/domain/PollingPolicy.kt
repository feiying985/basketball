package com.feiying.basketball.domain

/**
 * Polling and retry timings for an analysis job.
 *
 * The server exposes no progress, so the UI only ever shows status plus elapsed time.
 */
object PollingPolicy {
    const val TOTAL_TIMEOUT_MS: Long = 5L * 60 * 1000
    const val MAX_NETWORK_BACKOFF_MS: Long = 15_000
    const val MAX_CONSECUTIVE_SERVER_ERRORS: Int = 3

    /** 1 s for the first 10 s, 2 s up to 30 s, then 3 s. */
    fun pollIntervalMs(elapsedMs: Long): Long = when {
        elapsedMs < 10_000 -> 1_000
        elapsedMs < 30_000 -> 2_000
        else -> 3_000
    }

    /** 2 s, 4 s, 8 s, then capped at 15 s. */
    fun networkBackoffMs(attempt: Int): Long {
        if (attempt <= 0) return 2_000
        val exponent = (attempt - 1).coerceAtMost(10)
        return (2_000L shl exponent).coerceAtMost(MAX_NETWORK_BACKOFF_MS)
    }

    fun isOverTotalTimeout(elapsedMs: Long): Boolean = elapsedMs > TOTAL_TIMEOUT_MS
}
