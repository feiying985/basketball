package com.feiying.basketball.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing number, time and date formatting. */
object Formatters {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

    /** `mm:ss`, or `h:mm:ss` for videos of an hour or more. */
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "--:--"
        val totalSeconds = ms / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** `mm:ss`, always two-digit minutes; used for shot timestamps. */
    fun shotTime(seconds: Double): String {
        val totalSeconds = seconds.coerceAtLeast(0.0).toLong()
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.ROOT, "%.1f MB", mb)
    }

    fun percent(made: Int, total: Int): Int {
        if (total <= 0) return 0
        return Math.round(made * 100.0 / total).toInt()
    }

    /**
     * History timestamps: `今天 HH:mm`, `昨天 HH:mm`, `M月d日 HH:mm`, or a full date with
     * year when the record is from a previous year.
     */
    fun historyTime(
        createdAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val created = Instant.ofEpochMilli(createdAtMillis).atZone(zoneId)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val date = created.toLocalDate()
        val time = created.format(timeFormatter)
        return when {
            date == today -> "今天 $time"
            date == today.minusDays(1) -> "昨天 $time"
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 $time"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日 $time"
        }
    }
}
