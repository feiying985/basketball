package com.feiying.basketball.data.ondevice

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import com.feiying.basketball.data.OnDeviceAnalysisException
import com.feiying.basketball.data.OnDeviceAnalyzer
import com.feiying.basketball.detection.BallPoint
import com.feiying.basketball.detection.DetectionConfig
import com.feiying.basketball.detection.Hoop
import com.feiying.basketball.detection.HoopDetection
import com.feiying.basketball.detection.PersistenceMap
import com.feiying.basketball.detection.Vision
import com.feiying.basketball.detection.buildTracks
import com.feiying.basketball.detection.detectShotEvents
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.HoopRoi
import com.feiying.basketball.domain.ShotEvent
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * On-device implementation of the classical shot detector.
 *
 * It samples the local MP4 at [DetectionConfig.sampleFps], extracts frames with
 * [MediaMetadataRetriever], and then runs the same orange-mask / distance-peak
 * / trajectory pipeline as the server. No network call is made and the video
 * file is only read from the app cache.
 *
 * This deliberately does not generate shot clips: clipping would require
 * re-muxing the video, and the result screen can already seek the original
 * video to each detected event.
 */
class OnDeviceShotAnalyzer(
    private val config: DetectionConfig = DetectionConfig(),
) : OnDeviceAnalyzer {

    override suspend fun analyze(
        videoFile: File,
        hoopRoi: HoopRoi?,
        durationMs: Long?,
        onProgress: (Float) -> Unit,
    ): AnalysisResult = withContext(Dispatchers.Default) {
        analyzeVideo(videoFile, hoopRoi, durationMs, onProgress)
    }

    private suspend fun analyzeVideo(
        videoFile: File,
        hoopRoi: HoopRoi?,
        durationMs: Long?,
        onProgress: (Float) -> Unit,
    ): AnalysisResult {
        if (!videoFile.exists()) {
            throw OnDeviceAnalysisException("本地视频已丢失，请重新录制或选择")
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            var rawWidth = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var rawHeight = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (rawWidth <= 0 || rawHeight <= 0) {
                val probe = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw OnDeviceAnalysisException("无法读取该视频画面，请换一段视频试试")
                rawWidth = probe.width
                rawHeight = probe.height
                probe.recycle()
            }
            val rotation = metadataInt(
                retriever,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            ) % 360
            val quarterTurn = rotation % 180 != 0
            val displayWidth = if (quarterTurn) rawHeight else rawWidth
            val displayHeight = if (quarterTurn) rawWidth else rawHeight
            val scale = min(
                1.0,
                config.maxAnalysisWidth.toDouble() / displayWidth.toDouble(),
            )
            val targetWidth = max(2, (displayWidth * scale).roundToInt())
            val targetHeight = max(2, (displayHeight * scale).roundToInt())
            val rawScaledWidth = max(2, (rawWidth * scale).roundToInt())
            val rawScaledHeight = max(2, (rawHeight * scale).roundToInt())

            val totalDurationMs = metadataInt(
                retriever,
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            ).takeIf { it > 0 }?.toLong()
                ?: durationMs?.takeIf { it > 0 }
                ?: throw OnDeviceAnalysisException("无法读取视频时长，请换一段视频试试")

            val frameCount = max(
                1,
                ceil(totalDurationMs / 1000.0 * config.sampleFps).toInt(),
            )
            val pixels = IntArray(targetWidth * targetHeight)
            val persistent = PersistenceMap(
                width = targetWidth,
                height = targetHeight,
                alpha = config.persistenceAlpha.toFloat(),
            )
            val detections = ArrayList<List<BallPoint>>(frameCount)
            var consecutiveFailures = 0
            var decodedFrames = 0

            for (sampleIndex in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val timeUs = (sampleIndex * 1_000_000.0 / config.sampleFps)
                    .toLong()
                    .coerceAtMost(totalDurationMs * 1000L - 1)
                val raw = runCatching {
                    decodeFrame(
                        retriever = retriever,
                        timeUs = timeUs,
                        rawWidth = rawScaledWidth,
                        rawHeight = rawScaledHeight,
                    )
                }.getOrNull()
                if (raw == null) {
                    consecutiveFailures++
                    detections.add(emptyList())
                    if (decodedFrames == 0 && consecutiveFailures >= 8) {
                        throw OnDeviceAnalysisException("无法解码该视频，请换一段 MP4 视频试试")
                    }
                    continue
                }
                consecutiveFailures = 0
                decodedFrames++
                val frame = orient(raw, rotation, targetWidth, targetHeight)
                try {
                    frame.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
                    val mask = Vision.cleanMask(
                        Vision.orangeMask(pixels, targetWidth, targetHeight, config),
                        targetWidth,
                        targetHeight,
                    )
                    persistent.update(mask)
                    val timestamp = timeUs / 1_000_000.0
                    detections.add(
                        Vision.ballCandidates(
                            mask = mask,
                            persistent = persistent.values,
                            width = targetWidth,
                            height = targetHeight,
                            frame = sampleIndex,
                            timestamp = timestamp,
                            config = config,
                        ),
                    )
                } finally {
                    frame.recycle()
                }
                if (sampleIndex % 3 == 0 || sampleIndex == frameCount - 1) {
                    onProgress(((sampleIndex + 1).toFloat() / frameCount).coerceIn(0f, 1f))
                }
            }

            if (decodedFrames == 0) {
                throw OnDeviceAnalysisException("视频中没有可读取的画面。")
            }

            val hoopDetection = if (hoopRoi != null) {
                HoopDetection(
                    hoop = Hoop(
                        centerX = (hoopRoi.x + hoopRoi.width / 2f) * targetWidth,
                        centerY = (hoopRoi.y + hoopRoi.height / 2f) * targetHeight,
                        width = hoopRoi.width * targetWidth,
                        height = hoopRoi.height * targetHeight,
                    ),
                    confidence = 1.0,
                )
            } else {
                Vision.findHoop(persistent.values, targetWidth, targetHeight, config)
            }

            if (hoopDetection == null) {
                return AnalysisResult(
                    detector = DETECTOR_NAME,
                    shots = emptyList(),
                    note = NO_HOOP_NOTE,
                    clipNote = LOCAL_CLIP_NOTE,
                )
            }
            if (hoopRoi == null &&
                config.hoopMinConfidence > 0.0 &&
                hoopDetection.confidence < config.hoopMinConfidence
            ) {
                return AnalysisResult(
                    detector = DETECTOR_NAME,
                    shots = emptyList(),
                    note = HOOP_AMBIGUOUS_NOTE,
                    clipNote = LOCAL_CLIP_NOTE,
                )
            }

            val tracks = buildTracks(
                detections = detections,
                frameWidth = targetWidth,
                frameHeight = targetHeight,
                maxGap = config.maxTrackGap,
                minPoints = config.minTrackPoints,
                minVerticalRangeRatio = config.minVerticalRangeRatio,
            )
            if (tracks.isEmpty()) {
                return AnalysisResult(
                    detector = DETECTOR_NAME,
                    shots = emptyList(),
                    note = NO_BALL_NOTE,
                    clipNote = LOCAL_CLIP_NOTE,
                )
            }

            val events = detectShotEvents(
                tracks = tracks,
                hoop = hoopDetection.hoop,
                frameHeight = targetHeight,
                cooldownSeconds = config.shotCooldownSeconds,
            )
            if (events.isEmpty()) {
                return AnalysisResult(
                    detector = DETECTOR_NAME,
                    shots = emptyList(),
                    note = NO_EVENT_NOTE,
                    clipNote = LOCAL_CLIP_NOTE,
                )
            }

            return AnalysisResult(
                detector = DETECTOR_NAME,
                shots = events.map { event ->
                    ShotEvent(
                        timestampSeconds = (event.timestamp * 1000).roundToInt() / 1000.0,
                        frameIndex = (event.timestamp * config.sampleFps).roundToInt(),
                        made = event.made,
                    )
                },
                clipNote = LOCAL_CLIP_NOTE,
            )
        } catch (error: OnDeviceAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw OnDeviceAnalysisException(
                error.message ?: "本地识别失败，请重试或换一段视频",
                error,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        rawWidth: Int,
        rawHeight: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                rawWidth,
                rawHeight,
            )
        }
        val raw = retriever.getFrameAtTime(
            timeUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return null
        if (raw.width == rawWidth && raw.height == rawHeight) return raw
        val scaled = Bitmap.createScaledBitmap(raw, rawWidth, rawHeight, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /**
     * Applies the video rotation metadata and scales to the fixed analysis
     * size. The metadata rotation is not applied by [MediaMetadataRetriever],
     * and the hoop ROI picker applies it the same way, so ROIs line up.
     */
    private fun orient(
        source: Bitmap,
        rotation: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        var current = source
        if (rotation % 360 != 0) {
            val rotated = Bitmap.createBitmap(
                current,
                0,
                0,
                current.width,
                current.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
            if (rotated !== current) {
                current.recycle()
                current = rotated
            }
        }
        if (current.width != targetWidth || current.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(
                current,
                targetWidth,
                targetHeight,
                true,
            )
            if (scaled !== current) {
                current.recycle()
                current = scaled
            }
        }
        return current
    }

    private fun metadataInt(retriever: MediaMetadataRetriever, key: Int): Int =
        retriever.extractMetadata(key)?.toIntOrNull() ?: 0

    companion object {
        const val DETECTOR_NAME = "on-device"

        const val LOCAL_CLIP_NOTE =
            "本地识别不生成进球片段；点击投篮事件可在原视频中定位。"

        private const val NO_HOOP_NOTE =
            "未检测到篮筐：本地识别需要画面中持续可见的橙色/红色篮筐，" +
                "建议在确认视频时手动圈定篮筐。"

        private const val HOOP_AMBIGUOUS_NOTE =
            "自动找筐置信度不足：画面中可能有多个橙色物体，" +
                "请手动圈定篮筐后重新识别。"

        private const val NO_BALL_NOTE =
            "未检测到可跟踪的篮球：当前基线依赖橙色球的颜色特征。"

        private const val NO_EVENT_NOTE =
            "检测到篮球轨迹，但未识别到经过篮筐的投篮。"
    }
}
