package com.feiying.basketball.ui.analysis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.feiying.basketball.R
import com.feiying.basketball.data.ActiveAnalysis
import com.feiying.basketball.data.ActivePhase
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.ui.common.ScreenHeader
import com.feiying.basketball.ui.common.message
import com.feiying.basketball.ui.navigation.Routes
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun AnalysisRoute(
    container: AppContainer,
    navController: NavHostController,
    recordId: String,
) {
    val repository = container.repository
    val records by repository.records.collectAsStateWithLifecycle()
    val activeMap by repository.active.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.localId == recordId }
    val active = activeMap[recordId]
    var showCancelDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var waitedForRecords by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(700)
        waitedForRecords = true
    }
    LaunchedEffect(record?.status) {
        if (record != null &&
            (record.status == AnalysisStatus.QUEUED ||
                record.status == AnalysisStatus.PROCESSING)
        ) {
            repository.resume(recordId)
        }
    }

    val previousRoute = navController.previousBackStackEntry?.destination?.route
    LaunchedEffect(record?.status) {
        if (record != null && record.status == AnalysisStatus.COMPLETED) {
            val routeToPop = if (previousRoute == Routes.REVIEW) {
                Routes.REVIEW
            } else {
                Routes.ANALYSIS_PATTERN
            }
            navController.navigate(Routes.result(record.localId)) {
                popUpTo(routeToPop) { inclusive = true }
            }
        }
    }

    if (record == null) {
        if (!waitedForRecords) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp),
            ) {
                ScreenHeader(title = stringResource(R.string.analysis_title))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
            ) {
                ScreenHeader(
                    title = stringResource(R.string.analysis_title),
                    onBack = { navController.popBackStack() },
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.analysis_not_found),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        return
    }

    val isUploading = record.status == AnalysisStatus.UPLOADING
    val isWaiting = record.status == AnalysisStatus.QUEUED ||
        record.status == AnalysisStatus.PROCESSING
    BackHandler {
        when {
            isUploading -> showCancelDialog = true
            isWaiting -> showLeaveDialog = true
            else -> navController.popBackStack()
        }
    }

    AnalysisContent(
        record = record,
        active = active,
        onBack = {
            when {
                isUploading -> showCancelDialog = true
                isWaiting -> showLeaveDialog = true
                else -> navController.popBackStack()
            }
        },
        onCancelUpload = { showCancelDialog = true },
        onLeave = { showLeaveDialog = true },
        onContinueWaiting = { repository.continueWaiting(recordId) },
        onRetry = { repository.retry(recordId) },
        onHome = { navController.popBackStack(Routes.HOME, inclusive = false) },
    )

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.analysis_cancel_title)) },
            text = { Text(stringResource(R.string.analysis_cancel_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        container.repository.cancelUpload(recordId)
                        navController.popBackStack()
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.analysis_leave_title)) },
            text = { Text(stringResource(R.string.analysis_leave_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveDialog = false
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                ) {
                    Text(stringResource(R.string.analysis_leave_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun AnalysisContent(
    record: AnalysisRecord,
    active: ActiveAnalysis?,
    onBack: () -> Unit,
    onCancelUpload: () -> Unit,
    onLeave: () -> Unit,
    onContinueWaiting: () -> Unit,
    onRetry: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.analysis_title),
            onBack = onBack,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusIcon(record.status)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = statusTitle(record, active),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = record.filename,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(Modifier.height(20.dp))
                when (record.status) {
                    AnalysisStatus.UPLOADING -> UploadingBody(active)
                    AnalysisStatus.QUEUED,
                    AnalysisStatus.PROCESSING,
                    -> WaitingBody(record, active)

                    AnalysisStatus.UPLOAD_INTERRUPTED -> {
                        Text(
                            stringResource(R.string.analysis_upload_interrupted),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    AnalysisStatus.FAILED,
                    AnalysisStatus.EXPIRED,
                    -> {
                        Text(
                            text = record.errorCode?.message(record.errorDetail)
                                ?: stringResource(R.string.error_unknown),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    AnalysisStatus.COMPLETED -> Text(
                        stringResource(R.string.analysis_opening_result),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        when (record.status) {
            AnalysisStatus.UPLOADING -> {
                OutlinedButton(
                    onClick = onCancelUpload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.analysis_cancel_upload))
                }
            }

            AnalysisStatus.QUEUED, AnalysisStatus.PROCESSING -> {
                val timedOut = active?.timedOut == true
                if (timedOut) {
                    Text(
                        text = stringResource(R.string.analysis_timeout_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.analysis_timeout_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Button(
                        onClick = onContinueWaiting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(stringResource(R.string.analysis_continue_wait))
                    }
                    TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.analysis_retry))
                    }
                } else {
                    OutlinedButton(
                        onClick = onLeave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(stringResource(R.string.action_home))
                    }
                }
            }

            AnalysisStatus.UPLOAD_INTERRUPTED,
            AnalysisStatus.FAILED,
            AnalysisStatus.EXPIRED,
            -> {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.analysis_retry))
                }
                TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_home))
                }
            }

            else -> Unit
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusIcon(status: AnalysisStatus) {
    val icon = when (status) {
        AnalysisStatus.COMPLETED -> Icons.Filled.CheckCircle
        AnalysisStatus.FAILED, AnalysisStatus.EXPIRED -> Icons.Filled.ErrorOutline
        AnalysisStatus.UPLOADING -> Icons.Filled.Upload
        else -> Icons.Filled.HourglassTop
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = when (status) {
            AnalysisStatus.COMPLETED -> MaterialTheme.colorScheme.primary
            AnalysisStatus.FAILED, AnalysisStatus.EXPIRED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        },
    )
}

@Composable
private fun statusTitle(record: AnalysisRecord, active: ActiveAnalysis?): String = when (record.status) {
    AnalysisStatus.UPLOADING -> stringResource(R.string.analysis_uploading)
    AnalysisStatus.QUEUED -> stringResource(R.string.analysis_queued)
    AnalysisStatus.PROCESSING -> when {
        active?.phase == ActivePhase.NETWORK_RETRY ->
            stringResource(R.string.analysis_network_retry)

        active?.stage == "clipping" -> stringResource(R.string.analysis_clipping)
        else -> stringResource(R.string.analysis_processing)
    }

    AnalysisStatus.UPLOAD_INTERRUPTED -> stringResource(R.string.analysis_upload_interrupted)
    AnalysisStatus.COMPLETED -> stringResource(R.string.history_status_completed)
    AnalysisStatus.FAILED -> record.errorCode?.message(record.errorDetail)
        ?: stringResource(R.string.history_status_failed)

    AnalysisStatus.EXPIRED -> stringResource(R.string.history_status_expired)
}

@Composable
private fun UploadingBody(active: ActiveAnalysis?) {
    val progress = active?.progress
    LinearProgressIndicator(
        progress = { progress ?: 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (progress != null) {
                stringResource(R.string.analysis_upload_percent, (progress * 100).roundToInt())
            } else {
                stringResource(R.string.analysis_uploading)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (active != null && active.totalBytes > 0) {
                stringResource(
                    R.string.analysis_upload_detail,
                    Formatters.fileSize(active.bytesSent),
                    Formatters.fileSize(active.totalBytes),
                )
            } else {
                ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaitingBody(record: AnalysisRecord, active: ActiveAnalysis?) {
    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
    Spacer(Modifier.height(14.dp))
    if (active != null) {
        Text(
            text = stringResource(
                R.string.analysis_elapsed,
                Formatters.duration(active.elapsedMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text = stringResource(R.string.analysis_hint_leave),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.analysis_hint_network),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (active?.phase == ActivePhase.NETWORK_RETRY) {
        Text(
            text = stringResource(R.string.analysis_network_retry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
