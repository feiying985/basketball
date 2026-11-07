package com.feiying.basketball.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.feiying.basketball.data.ActiveAnalysis
import com.feiying.basketball.data.ActivePhase
import com.feiying.basketball.data.HealthState
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.ui.AppScaffold
import com.feiying.basketball.ui.analysis.AnalysisContent
import com.feiying.basketball.ui.history.HistoryContent
import com.feiying.basketball.ui.home.HomeContent
import com.feiying.basketball.ui.legacy.LegacyHomeScreen
import com.feiying.basketball.ui.navigation.Routes
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
 * Renders the baseline and the new screens with the real Compose UI at an identical
 * viewport, theme and state. The generated PNGs are used as the PR's visual evidence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp-xhdpi",
    application = ScreenshotApplication::class,
)
class HomeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun beforeHome() {
        compose.setContent {
            BasketballTheme(darkTheme = false) {
                LegacyHomeScreen()
            }
        }
        capture("home-before.png")
    }

    @Test
    fun lightHome() {
        setAppContent(Routes.HOME, darkTheme = false) {
            HomeContent(
                records = emptyList(),
                health = HealthState.Unknown,
                onStartAnalysis = {},
                onOpenRecord = {},
                onCheckHealth = {},
                onDeveloperSettings = {},
                onOpenAccount = {},
            )
        }
        capture("home-light.png")
    }

    @Test
    fun afterHome() {
        setAppContent(Routes.HOME) {
            HomeContent(
                records = emptyList(),
                health = HealthState.Unknown,
                onStartAnalysis = {},
                onOpenRecord = {},
                onCheckHealth = {},
                onDeveloperSettings = {},
                onOpenAccount = {},
            )
        }
        capture("home-after.png")
    }

    @Test
    fun afterHomeWithRecord() {
        setAppContent(Routes.HOME) {
            HomeContent(
                records = listOf(completedRecord(now = System.currentTimeMillis())),
                health = HealthState.Online(version = "0.1.0", detector = "stub"),
                onStartAnalysis = {},
                onOpenRecord = {},
                onCheckHealth = {},
                onDeveloperSettings = {},
                onOpenAccount = {},
            )
        }
        capture("home-with-record.png")
    }

    @Test
    fun afterHistory() {
        setAppContent(Routes.HISTORY) {
            HistoryContent(
                records = listOf(
                    processingRecord(now = System.currentTimeMillis()),
                    completedRecord(now = System.currentTimeMillis() - 3_600_000),
                ),
                onOpen = {},
                onDelete = {},
                onStartAnalysis = {},
            )
        }
        capture("history.png")
    }

    @Test
    fun afterAnalysis() {
        val record = processingRecord(now = System.currentTimeMillis())
        setAppContent(Routes.ANALYSIS_PATTERN) {
            AnalysisContent(
                record = record,
                active = ActiveAnalysis(
                    phase = ActivePhase.PROCESSING,
                    elapsedMs = 42_000,
                ),
                onBack = {},
                onCancelUpload = {},
                onLeave = {},
                onContinueWaiting = {},
                onRetry = {},
                onHome = {},
            )
        }
        capture("analysis.png")
    }

    private fun setAppContent(
        route: String,
        darkTheme: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            BasketballTheme(darkTheme = darkTheme) {
                AppScaffold(currentRoute = route, onNavigate = {}) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        content()
                    }
                }
            }
        }
    }

    private fun capture(fileName: String) {
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: 822
        val height = decor.height.takeIf { it > 0 } ?: 1782
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

    private fun completedRecord(now: Long): AnalysisRecord = AnalysisRecord(
        localId = "completed-1",
        serverJobId = "job-completed",
        createdAt = now,
        source = VideoSource.RECORD,
        localVideoPath = null,
        filename = "rec_20261105_1810.mp4",
        sizeBytes = 42L * 1024 * 1024,
        durationMs = 95_000,
        status = AnalysisStatus.COMPLETED,
        result = AnalysisResult(
            detector = "stub",
            shots = listOf(
                ShotEvent(4.0, 80, true),
                ShotEvent(12.0, 240, true),
                ShotEvent(21.0, 420, false),
            ),
        ),
        updatedAt = now,
    )

    private fun processingRecord(now: Long): AnalysisRecord = AnalysisRecord(
        localId = "processing-1",
        serverJobId = "job-processing",
        createdAt = now,
        source = VideoSource.GALLERY,
        localVideoPath = null,
        filename = "training.mp4",
        sizeBytes = 18L * 1024 * 1024,
        durationMs = 62_000,
        status = AnalysisStatus.PROCESSING,
        updatedAt = now,
    )
}
