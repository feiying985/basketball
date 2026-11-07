package com.feiying.basketball.ui.clip

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.feiying.basketball.R
import com.feiying.basketball.data.ApiException
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.data.ClipSaveResult
import com.feiying.basketball.data.ClipSaver
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.ui.common.ScreenHeader
import com.feiying.basketball.ui.common.VideoPlayer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface ClipSaveState {
    data object Idle : ClipSaveState
    data class Downloading(val progress: Float?) : ClipSaveState
    data object Saving : ClipSaveState
}

@Composable
fun ClipPlayerRoute(
    container: AppContainer,
    navController: NavHostController,
    recordId: String,
    index: Int,
) {
    val records by container.repository.records.collectAsStateWithLifecycle()
    val record = records.firstOrNull { it.localId == recordId }
    val shot = record?.result?.shots?.getOrNull(index)
    val clip = shot?.clip
    val onBack: () -> Unit = { navController.popBackStack() }

    if (record == null || shot == null || clip == null) {
        ClipUnavailableContent(
            message = stringResource(R.string.clip_not_found),
            onBack = onBack,
        )
        return
    }
    if (!clip.isReady) {
        ClipUnavailableContent(
            message = stringResource(R.string.clip_not_ready),
            onBack = onBack,
        )
        return
    }
    val jobId = record.serverJobId
    if (jobId == null) {
        ClipUnavailableContent(
            message = stringResource(R.string.clip_expired),
            onBack = onBack,
        )
        return
    }

    val repository = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var clipUrl by remember(jobId, index) { mutableStateOf<String?>(null) }
    var clipHeaders by remember(jobId, index) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var loadFailed by remember(jobId, index) { mutableStateOf(false) }
    var saveState by remember { mutableStateOf<ClipSaveState>(ClipSaveState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(jobId, index) {
        try {
            clipUrl = repository.clipUrl(jobId, index)
            clipHeaders = container.authToken()?.let { token ->
                mapOf("Authorization" to "Bearer $token")
            } ?: emptyMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadFailed = true
        }
    }

    if (loadFailed) {
        ClipUnavailableContent(
            message = stringResource(R.string.clip_load_failed),
            onBack = onBack,
        )
        return
    }

    fun startDownloadAndSave() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            saveState = ClipSaveState.Downloading(null)
            val cacheFile = File(context.cacheDir, "clips/${jobId}_$index.mp4")
            try {
                repository.downloadClip(jobId, index, cacheFile) { received, total ->
                    saveState = ClipSaveState.Downloading(
                        progress = if (total != null && total > 0) {
                            (received.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                }
                saveState = ClipSaveState.Saving
                when (
                    withContext(Dispatchers.IO) {
                        ClipSaver.save(
                            context = context,
                            source = cacheFile,
                            displayName = "basketball_${jobId.take(8)}_${index + 1}.mp4",
                        )
                    }
                ) {
                    is ClipSaveResult.Saved -> {
                        saveState = ClipSaveState.Idle
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.clip_saved),
                        )
                    }

                    is ClipSaveResult.Failed -> {
                        saveState = ClipSaveState.Idle
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.clip_save_failed),
                        )
                    }
                }
            } catch (error: CancellationException) {
                saveState = ClipSaveState.Idle
                throw error
            } catch (error: ApiException) {
                saveState = ClipSaveState.Idle
                snackbarHostState.showSnackbar(
                    context.getString(
                        when (error.statusCode) {
                            404 -> R.string.clip_expired
                            409 -> R.string.clip_not_ready
                            else -> R.string.clip_download_failed
                        },
                    ),
                )
            } catch (error: Exception) {
                saveState = ClipSaveState.Idle
                snackbarHostState.showSnackbar(
                    context.getString(R.string.clip_save_failed),
                )
            } finally {
                cacheFile.delete()
            }
        }
    }

    val needsStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startDownloadAndSave()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.clip_permission_denied),
                )
            }
        }
    }

    fun requestSave() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (needsStoragePermission && !hasPermission) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownloadAndSave()
        }
    }

    Box(Modifier.fillMaxSize()) {
        ClipPlayerContent(
            shotNumber = index + 1,
            shotTime = Formatters.shotTime(shot.timestampSeconds),
            rangeText = stringResource(
                R.string.clip_range,
                Formatters.shotTime(clip.startSeconds),
                Formatters.shotTime(clip.endSeconds),
            ),
            clipUrl = clipUrl,
            clipHeaders = clipHeaders,
            saving = saveState !is ClipSaveState.Idle,
            onBack = onBack,
            onSave = { requestSave() },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }

    val currentSaveState = saveState
    if (currentSaveState is ClipSaveState.Downloading ||
        currentSaveState is ClipSaveState.Saving
    ) {
        AlertDialog(
            onDismissRequest = {
                if (currentSaveState is ClipSaveState.Downloading) downloadJob?.cancel()
            },
            title = { Text(stringResource(R.string.clip_save_title)) },
            text = {
                Column {
                    if (currentSaveState is ClipSaveState.Downloading) {
                        val progress = currentSaveState.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = if (progress != null) {
                                stringResource(
                                    R.string.clip_downloading_percent,
                                    (progress * 100).roundToInt(),
                                )
                            } else {
                                stringResource(R.string.clip_downloading)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.clip_saving),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (currentSaveState is ClipSaveState.Downloading) {
                    TextButton(onClick = { downloadJob?.cancel() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

/** Shared clip layout; screenshot tests render it with a placeholder player. */
@Composable
fun ClipPlayerContent(
    shotNumber: Int,
    shotTime: String,
    rangeText: String,
    clipUrl: String?,
    clipHeaders: Map<String, String> = emptyMap(),
    saving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    playerSlot: (@Composable (Modifier) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.clip_page_title), onBack = onBack)
        Text(
            text = stringResource(R.string.clip_title, shotNumber, shotTime),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = rangeText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = Color.Black,
        ) {
            when {
                playerSlot != null -> playerSlot(Modifier.fillMaxSize())
                clipUrl != null -> VideoPlayer(
                    uri = Uri.parse(clipUrl),
                    headers = clipHeaders,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        Text(
            text = stringResource(R.string.clip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(imageVector = Icons.Filled.Download, contentDescription = null)
            Text(
                text = stringResource(R.string.clip_save_to_album),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ClipUnavailableContent(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        ScreenHeader(title = stringResource(R.string.clip_page_title), onBack = onBack)
        Spacer(Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
        Spacer(Modifier.height(16.dp))
    }
}
