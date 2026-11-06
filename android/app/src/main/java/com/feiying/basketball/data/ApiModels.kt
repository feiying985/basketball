package com.feiying.basketball.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisJobDto(
    val id: String,
    val status: String,
    val filename: String,
    @SerialName("created_at") val createdAt: String,
    val result: AnalysisResultDto? = null,
    val error: String? = null,
    val stage: String? = null,
)

@Serializable
data class AnalysisResultDto(
    val detector: String,
    val shots: List<ShotEventDto> = emptyList(),
    val note: String? = null,
    @SerialName("clip_note") val clipNote: String? = null,
)

@Serializable
data class ShotEventDto(
    @SerialName("timestamp_seconds") val timestampSeconds: Double,
    @SerialName("frame_index") val frameIndex: Int,
    val made: Boolean,
    val clip: ShotClipDto? = null,
)

@Serializable
data class ShotClipDto(
    val status: String,
    @SerialName("start_seconds") val startSeconds: Double = 0.0,
    @SerialName("end_seconds") val endSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
data class HealthDto(
    val status: String,
    val service: String,
    val version: String,
    val detector: String,
    val clips: Boolean = false,
)
