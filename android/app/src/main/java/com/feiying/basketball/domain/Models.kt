package com.feiying.basketball.domain

import java.io.File
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
data class ShotEvent(
    val timestampSeconds: Double,
    val frameIndex: Int,
    val made: Boolean,
)

@Serializable
data class AnalysisResult(
    val detector: String,
    val shots: List<ShotEvent> = emptyList(),
    val note: String? = null,
) {
    val madeCount: Int get() = shots.count { it.made }
    val totalCount: Int get() = shots.size
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
