package com.feiying.basketball.domain

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `duration formats minutes and seconds`() {
        assertEquals("00:00", Formatters.duration(0))
        assertEquals("00:08", Formatters.duration(8_000))
        assertEquals("01:01", Formatters.duration(61_000))
        assertEquals("20:00", Formatters.duration(20 * 60 * 1000L))
        assertEquals("--:--", Formatters.duration(null))
    }

    @Test
    fun `duration includes hours when needed`() {
        assertEquals("1:02:03", Formatters.duration(3_723_000))
    }

    @Test
    fun `shot time is zero padded`() {
        assertEquals("00:04", Formatters.shotTime(4.2))
        assertEquals("12:05", Formatters.shotTime(725.9))
    }

    @Test
    fun `clip duration uses one decimal`() {
        assertEquals("10.0s", Formatters.clipDuration(10.0))
        assertEquals("9.5s", Formatters.clipDuration(9.46))
        assertEquals("0.0s", Formatters.clipDuration(-1.0))
    }

    @Test
    fun `file size uses readable units`() {
        assertEquals("512 B", Formatters.fileSize(512))
        assertEquals("1.5 KB", Formatters.fileSize(1536))
        assertEquals("2.0 MB", Formatters.fileSize(2L * 1024 * 1024))
    }

    @Test
    fun `percent rounds to nearest integer`() {
        assertEquals(60, Formatters.percent(3, 5))
        assertEquals(67, Formatters.percent(2, 3))
        assertEquals(0, Formatters.percent(0, 0))
    }

    @Test
    fun `history time uses relative labels`() {
        val now = instantMillis(2026, 11, 5, 18, 30)
        assertEquals(
            "今天 18:20",
            Formatters.historyTime(instantMillis(2026, 11, 5, 18, 20), now, zone),
        )
        assertEquals(
            "昨天 23:59",
            Formatters.historyTime(instantMillis(2026, 11, 4, 23, 59), now, zone),
        )
        assertEquals(
            "10月5日 09:00",
            Formatters.historyTime(instantMillis(2026, 10, 5, 9, 0), now, zone),
        )
    }

    @Test
    fun `history time includes year for older records`() {
        val now = instantMillis(2026, 11, 5, 18, 30)
        assertEquals(
            "2025年12月31日 08:05",
            Formatters.historyTime(instantMillis(2025, 12, 31, 8, 5), now, zone),
        )
    }

    private fun instantMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}
