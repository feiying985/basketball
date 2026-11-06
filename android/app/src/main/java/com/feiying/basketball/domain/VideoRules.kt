package com.feiying.basketball.domain

import java.util.Locale

/** Video format/size/duration rules shared by the review screen and tests. */
object VideoRules {
    const val MAX_UPLOAD_BYTES: Long = 200L * 1024 * 1024
    const val MAX_RECORDING_MS: Long = 20L * 60 * 1000
    const val MIN_RECORDING_MS: Long = 1_000
    const val LONG_VIDEO_WARNING_MS: Long = 60_000

    val allowedExtensions: Set<String> = setOf("mp4", "mov", "m4v", "webm", "avi", "mkv")

    fun extensionOf(filename: String): String? =
        filename.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= 5 }

    fun isAllowedExtension(filename: String): Boolean =
        extensionOf(filename) in allowedExtensions

    fun validate(
        filename: String,
        sizeBytes: Long,
        durationMs: Long?,
        readable: Boolean,
    ): VideoValidation {
        val issues = buildList {
            if (!isAllowedExtension(filename)) add(VideoIssue.UNSUPPORTED_FORMAT)
            if (sizeBytes > MAX_UPLOAD_BYTES) add(VideoIssue.TOO_LARGE)
            if (!readable) add(VideoIssue.UNREADABLE)
            if (durationMs != null && durationMs > LONG_VIDEO_WARNING_MS) {
                add(VideoIssue.LONG_VIDEO)
            }
        }
        return VideoValidation(issues)
    }

    /** Picks an extension from a MIME type when the picked file has no usable name. */
    fun extensionForMimeType(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo", "video/avi" -> "avi"
        "video/x-m4v" -> "m4v"
        else -> "mp4"
    }

    /** File name safe enough for a multipart part and the cache directory. */
    fun sanitizeFilename(filename: String, fallbackExtension: String): String {
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            .trim('.', '_')
        val name = cleaned.ifBlank { "video" }
        val extension = extensionOf(name)
        if (extension != null && extension in allowedExtensions) return name
        return "$name.${fallbackExtension.lowercase(Locale.ROOT)}"
    }

    fun buildCacheFilename(prefix: String, extension: String, timestamp: Long): String =
        "${prefix}_$timestamp.${extension.lowercase(Locale.ROOT)}"
}

enum class VideoIssue {
    UNSUPPORTED_FORMAT,
    TOO_LARGE,
    UNREADABLE,
    LONG_VIDEO,
}

data class VideoValidation(val issues: List<VideoIssue>) {
    val blockingIssues: List<VideoIssue>
        get() = issues.filter { it != VideoIssue.LONG_VIDEO }

    val canAnalyze: Boolean
        get() = blockingIssues.isEmpty()

    val hasLongWarning: Boolean
        get() = VideoIssue.LONG_VIDEO in issues
}
