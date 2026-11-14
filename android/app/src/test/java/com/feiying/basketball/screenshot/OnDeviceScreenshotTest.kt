package com.feiying.basketball.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.feiying.basketball.domain.AnalysisMode
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.HoopRoi
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.ui.result.ResultContent
import com.feiying.basketball.ui.review.ReviewContent
import com.feiying.basketball.ui.theme.BasketballTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual evidence for the recognition-mode choice and the local result banner.
 * The tall viewport keeps the whole review page visible so the before/after
 * comparison shows the same state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h1400dp-xhdpi",
    application = ScreenshotApplication::class,
)
class OnDeviceScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reviewCloudMode() {
        renderReview(AnalysisMode.CLOUD)
        capture("review-cloud.png")
    }

    @Test
    fun reviewLocalMode() {
        renderReview(AnalysisMode.ON_DEVICE)
        capture("review-local.png")
    }

    @Test
    fun localResult() {
        val record = AnalysisRecord(
            localId = "local-result",
            serverJobId = null,
            createdAt = System.currentTimeMillis(),
            source = VideoSource.GALLERY,
            localVideoPath = null,
            filename = "training.mp4",
            sizeBytes = 18L * 1024 * 1024,
            durationMs = 62_000,
            status = AnalysisStatus.COMPLETED,
            mode = AnalysisMode.ON_DEVICE,
            result = AnalysisResult(
                detector = "on-device",
                shots = listOf(
                    ShotEvent(4.0, 40, made = true),
                    ShotEvent(12.0, 120, made = true),
                    ShotEvent(21.0, 210, made = false),
                ),
                clipNote = "本地识别不生成进球片段；点击投篮事件可在原视频中定位。",
            ),
            updatedAt = System.currentTimeMillis(),
        )
        compose.setContent {
            BasketballTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ResultContent(
                        record = record,
                        onBack = {},
                        onOpenClip = {},
                        onReanalyze = {},
                        onDone = {},
                    )
                }
            }
        }
        capture("result-local.png")
    }

    private fun renderReview(mode: AnalysisMode) {
        val video = File(compose.activity.cacheDir, "night-game.mp4").apply {
            writeBytes(ByteArray(1024))
        }
        compose.setContent {
            BasketballTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ReviewContent(
                        video = SelectedVideo(
                            file = video,
                            filename = "night-game.mp4",
                            sizeBytes = 42L * 1024 * 1024,
                            durationMs = 95_000,
                            source = VideoSource.GALLERY,
                        ),
                        videoExists = true,
                        starting = false,
                        hoopRoi = HoopRoi(0.43f, 0.27f, 0.14f, 0.09f),
                        mode = mode,
                        onModeChange = {},
                        onBack = {},
                        onStart = {},
                        onReselect = {},
                        onRerecord = {},
                    )
                }
            }
        }
    }

    private fun capture(fileName: String) {
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: 822
        val height = decor.height.takeIf { it > 0 } ?: 2800
        if (decor.width == 0 || decor.height == 0) {
            decor.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    width,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    height,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
            )
            decor.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val output = File("build/screenshots/$fileName")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
