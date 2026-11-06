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
)

@Serializable
data class AnalysisResultDto(
    val detector: String,
    val shots: List<ShotEventDto> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ShotEventDto(
    @SerialName("timestamp_seconds") val timestampSeconds: Double,
    @SerialName("frame_index") val frameIndex: Int,
    val made: Boolean,
)

@Serializable
data class HealthDto(
    val status: String,
    val service: String,
    val version: String,
    val detector: String,
)
