package com.feiying.basketball.ui.review

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.feiying.basketball.R
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.ImportErrorReason
import com.feiying.basketball.data.VideoFiles
import com.feiying.basketball.data.VideoImportException
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.VideoIssue
import com.feiying.basketball.domain.VideoRules
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.domain.VideoValidation
import com.feiying.basketball.ui.common.ScreenHeader
import com.feiying.basketball.ui.common.VideoPlayer
import com.feiying.basketball.ui.common.messageResource
import com.feiying.basketball.ui.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun ReviewRoute(
    container: AppContainer,
    navController: NavHostController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by container.newAnalysisSession.selected.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        starting = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        starting = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = VideoFiles.importFromGallery(context, uri)
            importing = false
            result.onSuccess { container.newAnalysisSession.select(it) }
                .onFailure { error ->
                    val reason = (error as? VideoImportException)?.reason
                        ?: ImportErrorReason.UNREADABLE
                    snackbarHostState.showSnackbar(context.getString(reason.messageResource()))
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (selected == null) {
            ReviewMissingVideo(onBack = onBack)
        } else {
            val video = selected ?: return@Box
            ReviewContent(
                video = video,
                videoExists = video.file.exists(),
                starting = starting || importing,
                onBack = onBack,
                onStart = {
                    starting = true
                    scope.launch {
                        try {
                            val record = container.repository.createRecord(video)
                            container.repository.start(record.localId)
                            navController.navigate(Routes.analysis(record.localId))
                        } catch (error: Exception) {
                            starting = false
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.error_unknown),
                            )
                        }
                    }
                },
                onReselect = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onRerecord = {
                    navController.navigate(Routes.RECORD) {
                        popUpTo(Routes.REVIEW) { inclusive = true }
                    }
                },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun ReviewMissingVideo(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.review_title), onBack = onBack)
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.review_missing_video),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(2f))
    }
}

@Composable
fun ReviewContent(
    video: SelectedVideo,
    videoExists: Boolean,
    starting: Boolean,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onReselect: () -> Unit,
    onRerecord: () -> Unit,
) {
    val validation = remember(video) {
        VideoRules.validate(
            filename = video.filename,
            sizeBytes = video.sizeBytes,
            durationMs = video.durationMs,
            readable = video.durationMs != null,
        )
    }
    val canAnalyze = videoExists && validation.canAnalyze && !starting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.review_title), onBack = onBack)

        if (videoExists) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(20.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                VideoPlayer(
                    uri = Uri.fromFile(video.file),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = stringResource(R.string.review_missing_video),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        InfoRow(
            label = stringResource(R.string.review_filename_label),
            value = video.filename,
        )
        InfoRow(
            label = stringResource(R.string.review_duration_label),
            value = Formatters.duration(video.durationMs),
        )
        InfoRow(
            label = stringResource(R.string.review_size_label),
            value = Formatters.fileSize(video.sizeBytes),
        )
        InfoRow(
            label = stringResource(R.string.review_source_label),
            value = stringResource(
                if (video.source == VideoSource.RECORD) {
                    R.string.review_source_record
                } else {
                    R.string.review_source_gallery
                },
            ),
        )

        if (validation.issues.isNotEmpty() || !videoExists) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!videoExists) {
                    ValidationMessage(stringResource(R.string.review_missing_video), error = true)
                }
                validation.issues.forEach { issue ->
                    when (issue) {
                        VideoIssue.UNSUPPORTED_FORMAT ->
                            ValidationMessage(stringResource(R.string.review_invalid_format), true)

                        VideoIssue.TOO_LARGE ->
                            ValidationMessage(stringResource(R.string.review_too_large), true)

                        VideoIssue.UNREADABLE ->
                            ValidationMessage(stringResource(R.string.review_unreadable), true)

                        VideoIssue.LONG_VIDEO ->
                            ValidationMessage(stringResource(R.string.review_long_warning), false)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStart,
            enabled = canAnalyze,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.review_start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        TextButton(
            onClick = if (video.source == VideoSource.RECORD) onRerecord else onReselect,
            enabled = !starting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (video.source == VideoSource.RECORD) {
                        R.string.review_rerecord
                    } else {
                        R.string.review_reselect
                    },
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
    }
}

@Composable
private fun ValidationMessage(text: String, error: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.tertiary
        },
    )
}
