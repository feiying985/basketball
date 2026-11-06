package com.feiying.basketball.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.feiying.basketball.R
import com.feiying.basketball.data.ImportErrorReason
import com.feiying.basketball.domain.AnalysisError
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisStatus

@Composable
fun AnalysisStatus.label(): String = stringResource(
    when (this) {
        AnalysisStatus.UPLOADING -> R.string.history_status_uploading
        AnalysisStatus.UPLOAD_INTERRUPTED -> R.string.history_status_upload_interrupted
        AnalysisStatus.QUEUED -> R.string.history_status_queued
        AnalysisStatus.PROCESSING -> R.string.history_status_processing
        AnalysisStatus.COMPLETED -> R.string.history_status_completed
        AnalysisStatus.FAILED -> R.string.history_status_failed
        AnalysisStatus.EXPIRED -> R.string.history_status_expired
    },
)

@Composable
fun AnalysisStatus.badgeColor(): Color = when (this) {
    AnalysisStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    AnalysisStatus.FAILED, AnalysisStatus.EXPIRED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
fun AnalysisError.message(detail: String? = null): String {
    val base = stringResource(
        when (this) {
            AnalysisError.NETWORK -> R.string.error_network
            AnalysisError.UPLOAD_INTERRUPTED -> R.string.error_upload_interrupted
            AnalysisError.TOO_LARGE -> R.string.error_too_large
            AnalysisError.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
            AnalysisError.SERVER_ERROR -> R.string.error_server
            AnalysisError.JOB_EXPIRED -> R.string.error_expired
            AnalysisError.ANALYSIS_FAILED -> R.string.error_analysis_failed
            AnalysisError.TIMEOUT -> R.string.error_timeout
            AnalysisError.VIDEO_MISSING -> R.string.error_video_missing
            AnalysisError.UNKNOWN -> R.string.error_unknown
        },
    )
    return if (!detail.isNullOrBlank()) {
        if (this == AnalysisError.ANALYSIS_FAILED) {
            stringResource(R.string.result_failed_detail, detail)
        } else {
            stringResource(R.string.error_with_detail, base, detail)
        }
    } else {
        base
    }
}

@StringRes
fun ImportErrorReason.messageResource(): Int = when (this) {
    ImportErrorReason.UNREADABLE -> R.string.import_error_unreadable
    ImportErrorReason.TOO_LARGE -> R.string.import_error_too_large
    ImportErrorReason.STORAGE -> R.string.import_error_storage
    ImportErrorReason.COPY_FAILED -> R.string.import_error_copy
}

@Composable
fun ImportErrorReason.message(): String = stringResource(messageResource())

@Composable
fun AnalysisRecord.summaryText(): String = when (status) {
    AnalysisStatus.COMPLETED -> {
        if (result != null && result.shots.isNotEmpty()) {
            stringResource(R.string.history_summary_completed, result.shots.size, result.madeCount)
        } else {
            stringResource(R.string.home_recent_empty_summary)
        }
    }

    AnalysisStatus.UPLOADING -> stringResource(R.string.history_summary_uploading)
    AnalysisStatus.UPLOAD_INTERRUPTED -> stringResource(R.string.history_summary_upload_interrupted)
    AnalysisStatus.QUEUED -> stringResource(R.string.history_summary_queued)
    AnalysisStatus.PROCESSING -> stringResource(R.string.history_summary_processing)
    AnalysisStatus.FAILED -> stringResource(R.string.history_summary_failed)
    AnalysisStatus.EXPIRED -> stringResource(R.string.history_summary_expired)
}
