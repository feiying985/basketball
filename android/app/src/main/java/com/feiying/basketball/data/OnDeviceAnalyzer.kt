package com.feiying.basketball.data

import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.HoopRoi
import java.io.File

/**
 * Runs shot detection on the phone so the video never needs to be uploaded.
 *
 * Kept behind an interface so the repository can be tested without decoding
 * real video files.
 */
interface OnDeviceAnalyzer {
    suspend fun analyze(
        videoFile: File,
        hoopRoi: HoopRoi?,
        durationMs: Long?,
        onProgress: (Float) -> Unit,
    ): AnalysisResult
}

/** A failure that should be shown to the user as a local-analysis error. */
class OnDeviceAnalysisException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
