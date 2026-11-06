package com.feiying.basketball.domain

import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable

/** Where a video came from. */
enum class VideoSource {
    RECORD,
    GALLERY,
}

/**
 * Client-side analysis status.
 *
 * [UPLOADING] and [UPLOAD_INTERRUPTED] only exist locally; the other values mirror the
 * server's `JobStatus`. [EXPIRED] means the server no longer knows the task (usually a
 * server restart) while a local snapshot may still exist.
 */
enum class AnalysisStatus {
    UPLOADING,
    UPLOAD_INTERRUPTED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED;

    val isInProgress: Boolean
        get() = this == UPLOADING || this == QUEUED || this == PROCESSING

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == EXPIRED
}

@Serializable
enum class ClipStatus {
    READY,
    FAILED,
    UNKNOWN,
}

/** A server-generated MP4 covering a shot, or the reason it was not generated. */
@Serializable
data class ShotClip(
    val status: ClipStatus = ClipStatus.UNKNOWN,
    val startSeconds: Double = 0.0,
    val endSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val sizeBytes: Long? = null,
    val url: String? = null,
    val error: String? = null,
) {
    val isReady: Boolean
        get() = status == ClipStatus.READY && !url.isNullOrBlank()
}

@Serializable
data class ShotEvent(
    val timestampSeconds: Double,
    val frameIndex: Int,
    val made: Boolean,
    val clip: ShotClip? = null,
)

@Serializable
data class AnalysisResult(
    val detector: String,
    val shots: List<ShotEvent> = emptyList(),
    val note: String? = null,
    val clipNote: String? = null,
) {
    val madeCount: Int get() = shots.count { it.made }
    val totalCount: Int get() = shots.size

    /** Array indexes of made shots whose clip is ready; the server uses the same order. */
    val readyClipIndexes: List<Int>
        get() = shots.indices.filter { index ->
            val shot = shots[index]
            shot.made && shot.clip?.isReady == true
        }

    val hasClipAttempt: Boolean get() = shots.any { it.clip != null }
}

data class AnalysisRecord(
    val localId: String,
    val serverJobId: String?,
    val createdAt: Long,
    val source: VideoSource,
    val localVideoPath: String?,
    val filename: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val status: AnalysisStatus,
    val hoopRoi: HoopRoi? = null,
    val result: AnalysisResult? = null,
    val errorCode: AnalysisError? = null,
    val errorDetail: String? = null,
    val updatedAt: Long = createdAt,
) {
    val madeCount: Int get() = result?.madeCount ?: 0
    val totalShots: Int get() = result?.totalCount ?: 0
}

/** Machine-readable failure reasons; the UI maps them to localized messages. */
enum class AnalysisError {
    NETWORK,
    UPLOAD_INTERRUPTED,
    TOO_LARGE,
    UNSUPPORTED_FORMAT,
    SERVER_ERROR,
    JOB_EXPIRED,
    ANALYSIS_FAILED,
    TIMEOUT,
    VIDEO_MISSING,
    UNKNOWN,
}

/** A video the user picked or recorded, already copied into the app cache. */
data class SelectedVideo(
    val file: File,
    val filename: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val source: VideoSource,
)

/**
 * Normalized rectangle (0..1 of the video frame) marking the hoop.
 *
 * The server accepts the same `x,y,width,height` format as the
 * `BASKETBALL_HOOP_ROI` environment variable and the `hoop_roi` upload field.
 */
data class HoopRoi(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val isValid: Boolean
        get() = x >= 0f &&
            y >= 0f &&
            width > 0f &&
            height > 0f &&
            x + width <= 1.0001f &&
            y + height <= 1.0001f

    fun toFormValue(): String = String.format(
        Locale.US,
        "%.5f,%.5f,%.5f,%.5f",
        x,
        y,
        width,
        height,
    )

    companion object {
        fun parse(raw: String?): HoopRoi? {
            val parts = raw?.split(',')?.map { it.trim().toFloatOrNull() } ?: return null
            if (parts.size != 4) return null
            val values = parts.map { it ?: return null }
            return HoopRoi(values[0], values[1], values[2], values[3])
                .takeIf { it.isValid }
        }
    }
}
