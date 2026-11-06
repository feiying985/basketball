package com.feiying.basketball.ui.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiying.basketball.R
import com.feiying.basketball.domain.Formatters
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.VideoRules
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.ui.common.VideoPlayer
import com.feiying.basketball.ui.record.RecordCameraController.CameraStatus
import kotlinx.coroutines.delay

@Composable
fun RecordRoute(
    onClose: () -> Unit,
    onUseVideo: (SelectedVideo) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var showAudioWarning by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        audioGranted = result[Manifest.permission.RECORD_AUDIO] ?: audioGranted
        requestedOnce = true
        if (!cameraGranted) {
            permanentlyDenied = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        }
        if (!audioGranted) showAudioWarning = true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted || !audioGranted) {
            if (!requestedOnce) {
                requestedOnce = true
                val permissions = buildList {
                    if (!cameraGranted) add(Manifest.permission.CAMERA)
                    if (!audioGranted) add(Manifest.permission.RECORD_AUDIO)
                }
                if (permissions.isNotEmpty()) {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(showAudioWarning) {
        if (showAudioWarning) {
            snackbarHostState.showSnackbar(context.getString(R.string.record_microphone_denied))
            showAudioWarning = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!cameraGranted) {
            CameraPermissionPanel(
                permanentlyDenied = permanentlyDenied,
                onRequest = {
                    if (permanentlyDenied) {
                        openAppSettings(context)
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO,
                            ),
                        )
                    }
                },
                onOpenSettings = { openAppSettings(context) },
                onClose = onClose,
            )
        } else {
            val controller = remember {
                RecordCameraController(context, lifecycleOwner)
            }
            DisposableEffect(controller) {
                onDispose { controller.release() }
            }
            RecordCameraContent(
                controller = controller,
                withAudio = audioGranted,
                onClose = onClose,
                onUseVideo = onUseVideo,
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
private fun CameraPermissionPanel(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.record_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (permanentlyDenied) {
                stringResource(R.string.record_permission_denied)
            } else {
                stringResource(R.string.record_permission_rationale)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onRequest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(52.dp),
        ) {
            Text(
                stringResource(
                    if (permanentlyDenied) {
                        R.string.action_go_settings
                    } else {
                        R.string.record_request_permission
                    },
                ),
            )
        }
        if (!permanentlyDenied) {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_go_settings))
            }
        }
        Spacer(Modifier.weight(2f))
    }
}

@Composable
private fun RecordCameraContent(
    controller: RecordCameraController,
    withAudio: Boolean,
    onClose: () -> Unit,
    onUseVideo: (SelectedVideo) -> Unit,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val status = state.status
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(previewView) {
        controller.attachPreview(previewView)
        onDispose { }
    }

    var elapsedMs by remember { mutableLongStateOf(0L) }
    var maxReached by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var closeAfterDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (status is CameraStatus.Recording) {
            val started = SystemClock.elapsedRealtime()
            maxReached = false
            while (true) {
                elapsedMs = SystemClock.elapsedRealtime() - started
                if (elapsedMs >= VideoRules.MAX_RECORDING_MS) {
                    maxReached = true
                    controller.stopRecording()
                    break
                }
                delay(200)
            }
        } else {
            elapsedMs = 0L
        }
    }

    val inProgress = status is CameraStatus.Recording || status is CameraStatus.Finalizing
    BackHandler(enabled = inProgress || status is CameraStatus.Recorded) {
        if (status is CameraStatus.Recorded) {
            showDiscardDialog = true
            closeAfterDiscard = true
        } else {
            showDiscardDialog = true
            closeAfterDiscard = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        if (status is CameraStatus.Recorded) {
            VideoPlayer(
                uri = Uri.fromFile(status.file),
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.record_preview_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.record_preview_hint),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { controller.discardRecording() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.record_rerecord))
                        }
                        Button(
                            onClick = {
                                val file = status.file
                                onUseVideo(
                                    SelectedVideo(
                                        file = file,
                                        filename = file.name,
                                        sizeBytes = file.length(),
                                        durationMs = status.durationMs,
                                        source = VideoSource.RECORD,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.record_use_video))
                        }
                    }
                }
            }
        }

        // Top controls stay visible over both camera and preview.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (status is CameraStatus.Recording || status is CameraStatus.Recorded) {
                        showDiscardDialog = true
                        closeAfterDiscard = true
                    } else {
                        onClose()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            if (status !is CameraStatus.Recorded) {
                IconButton(onClick = { controller.toggleTorch() }, enabled = state.torchAvailable) {
                    Icon(
                        imageVector = if (state.torchOn) {
                            Icons.Filled.FlashOn
                        } else {
                            Icons.Filled.FlashOff
                        },
                        contentDescription = stringResource(
                            if (state.torchOn) R.string.record_torch_off else R.string.record_torch_on,
                        ),
                        tint = if (state.torchAvailable) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { controller.switchCamera() },
                    enabled = (state.hasFrontCamera && state.hasBackCamera) &&
                        status !is CameraStatus.Recording,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FlipCameraAndroid,
                        contentDescription = stringResource(R.string.record_switch_camera),
                        tint = Color.White,
                    )
                }
            }
        }

        if (status !is CameraStatus.Recorded) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (status) {
                    is CameraStatus.Initializing -> {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.record_preparing),
                            color = Color.White,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    is CameraStatus.Error -> {
                        Text(
                            text = stringResource(R.string.record_camera_error),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Button(
                            onClick = { controller.retryCamera() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }

                    CameraStatus.TooShort -> {
                        Text(
                            text = stringResource(R.string.record_too_short),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        RecordButton(onClick = { controller.startRecording(withAudio) })
                    }

                    is CameraStatus.Recording -> {
                        if (maxReached) {
                            Text(
                                text = stringResource(R.string.record_max_reached),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.record_timer,
                                Formatters.duration(elapsedMs),
                                Formatters.duration(VideoRules.MAX_RECORDING_MS),
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        StopButton(onClick = { controller.stopRecording() })
                    }

                    CameraStatus.Finalizing -> {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.record_finalizing),
                            color = Color.White,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.record_ready),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        RecordButton(onClick = { controller.startRecording(withAudio) })
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiscardDialog = false
                closeAfterDiscard = false
            },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        controller.discardRecording()
                        if (closeAfterDiscard) onClose()
                    },
                ) {
                    Text(stringResource(R.string.record_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        modifier = Modifier.size(84.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        modifier = Modifier.size(84.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color.White, RoundedCornerShape(6.dp)),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}
