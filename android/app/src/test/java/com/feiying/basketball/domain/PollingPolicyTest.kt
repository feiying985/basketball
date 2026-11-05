package com.feiying.basketball.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingPolicyTest {
    @Test
    fun `poll interval grows with elapsed time`() {
        assertEquals(1_000, PollingPolicy.pollIntervalMs(0))
        assertEquals(1_000, PollingPolicy.pollIntervalMs(9_999))
        assertEquals(2_000, PollingPolicy.pollIntervalMs(10_000))
        assertEquals(2_000, PollingPolicy.pollIntervalMs(29_999))
        assertEquals(3_000, PollingPolicy.pollIntervalMs(30_000))
        assertEquals(3_000, PollingPolicy.pollIntervalMs(120_000))
    }

    @Test
    fun `network backoff doubles and is capped`() {
        assertEquals(2_000, PollingPolicy.networkBackoffMs(1))
        assertEquals(4_000, PollingPolicy.networkBackoffMs(2))
        assertEquals(8_000, PollingPolicy.networkBackoffMs(3))
        assertEquals(15_000, PollingPolicy.networkBackoffMs(4))
        assertEquals(15_000, PollingPolicy.networkBackoffMs(20))
    }

    @Test
    fun `total timeout is five minutes`() {
        assertFalse(PollingPolicy.isOverTotalTimeout(300_000))
        assertTrue(PollingPolicy.isOverTotalTimeout(300_001))
    }
}
