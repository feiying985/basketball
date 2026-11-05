package com.feiying.basketball.ui.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.feiying.basketball.data.VideoFiles
import com.feiying.basketball.domain.VideoRules
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RecordCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    sealed interface CameraStatus {
        data object Initializing : CameraStatus
        data object Ready : CameraStatus
        data class Recording(val startedAtMillis: Long) : CameraStatus
        data object Finalizing : CameraStatus
        data class Recorded(val file: File, val durationMs: Long) : CameraStatus
        data object TooShort : CameraStatus
        data class Error(val detail: String?) : CameraStatus
    }

    data class UiState(
        val status: CameraStatus = CameraStatus.Initializing,
        val usingFrontCamera: Boolean = false,
        val hasFrontCamera: Boolean = false,
        val hasBackCamera: Boolean = false,
        val torchAvailable: Boolean = false,
        val torchOn: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            ),
        )
        .build()
    private val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)
    private val preview = Preview.Builder().build()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var recording: Recording? = null
    private var discardOnFinalize = false
    private var previewAttached = false

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && recording != null) {
            stopRecording()
        }
    }

    init {
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    val provider = cameraProvider
                    _state.update {
                        it.copy(
                            hasFrontCamera = provider?.hasCamera(
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                            ) == true,
                            hasBackCamera = provider?.hasCamera(
                                CameraSelector.DEFAULT_BACK_CAMERA,
                            ) == true,
                            usingFrontCamera = provider?.hasCamera(
                                CameraSelector.DEFAULT_BACK_CAMERA,
                            ) != true,
                        )
                    }
                    if (previewAttached) bindCamera()
                } catch (error: Exception) {
                    _state.update {
                        it.copy(status = CameraStatus.Error(error.message))
                    }
                }
            },
            mainExecutor,
        )
    }

    fun attachPreview(previewView: PreviewView) {
        preview.setSurfaceProvider(previewView.surfaceProvider)
        previewAttached = true
        if (cameraProvider != null) bindCamera()
    }

    fun startRecording(withAudio: Boolean) {
        val current = _state.value
        if (current.status !is CameraStatus.Ready && current.status !is CameraStatus.TooShort) return
        val file = VideoFiles.newRecordingFile(context)
        val outputOptions = FileOutputOptions.Builder(file).build()
        var pending = videoCapture.output.prepareRecording(context, outputOptions)
        if (withAudio && hasAudioPermission()) {
            try {
                pending = pending.withAudioEnabled()
            } catch (_: SecurityException) {
                // Fall back to a silent recording.
            }
        }
        discardOnFinalize = false
        _state.update { it.copy(status = CameraStatus.Recording(System.currentTimeMillis())) }
        try {
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> Unit
                    is VideoRecordEvent.Finalize -> handleFinalize(file, event)
                    else -> Unit
                }
            }
        } catch (error: Exception) {
            file.delete()
            _state.update {
                it.copy(status = CameraStatus.Error(error.message))
            }
        }
    }

    fun stopRecording() {
        if (recording == null) return
        _state.update { it.copy(status = CameraStatus.Finalizing) }
        recording?.stop()
        recording = null
    }

    fun discardRecording() {
        discardOnFinalize = true
        if (recording != null) {
            stopRecording()
        } else {
            val status = _state.value.status
            if (status is CameraStatus.Recorded) {
                status.file.delete()
                _state.update { it.copy(status = CameraStatus.Ready) }
            } else if (status !is CameraStatus.Finalizing) {
                _state.update { it.copy(status = CameraStatus.Ready) }
            }
        }
    }

    fun retryCamera() {
        _state.update { it.copy(status = CameraStatus.Initializing) }
        bindCamera()
    }

    fun switchCamera() {
        if (_state.value.status !is CameraStatus.Ready) return
        val current = _state.value
        val targetFront = if (current.usingFrontCamera) {
            if (!current.hasBackCamera) return
            false
        } else {
            if (!current.hasFrontCamera) return
            true
        }
        _state.update { it.copy(usingFrontCamera = targetFront, torchOn = false) }
        bindCamera()
    }

    fun toggleTorch() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) return
        val next = !_state.value.torchOn
        camera.cameraControl.enableTorch(next)
        _state.update { it.copy(torchOn = next) }
    }

    fun release() {
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        if (recording != null) {
            recording?.stop()
            recording = null
        }
        cameraProvider?.unbindAll()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val current = _state.value
        val selector = if (current.usingFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                videoCapture,
            )
            _state.update {
                it.copy(
                    status = when (it.status) {
                        is CameraStatus.Recorded,
                        is CameraStatus.Error,
                        CameraStatus.TooShort,
                        -> it.status

                        else -> CameraStatus.Ready
                    },
                    torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true,
                )
            }
        } catch (error: Exception) {
            _state.update {
                it.copy(status = CameraStatus.Error(error.message))
            }
        }
    }

    private fun handleFinalize(file: File, event: VideoRecordEvent.Finalize) {
        recording = null
        if (event.hasError()) {
            file.delete()
            _state.update {
                it.copy(status = CameraStatus.Error("CameraX error ${event.error}"))
            }
            return
        }
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        when {
            discardOnFinalize -> {
                file.delete()
                discardOnFinalize = false
                _state.update { it.copy(status = CameraStatus.Ready) }
            }

            durationMs < VideoRules.MIN_RECORDING_MS -> {
                file.delete()
                _state.update { it.copy(status = CameraStatus.TooShort) }
            }

            else -> {
                _state.update {
                    it.copy(status = CameraStatus.Recorded(file, durationMs))
                }
            }
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}
