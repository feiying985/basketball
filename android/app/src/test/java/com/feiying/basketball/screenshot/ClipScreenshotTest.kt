package com.feiying.basketball.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.feiying.basketball.R
import com.feiying.basketball.domain.AnalysisRecord
import com.feiying.basketball.domain.AnalysisResult
import com.feiying.basketball.domain.AnalysisStatus
import com.feiying.basketball.domain.ClipStatus
import com.feiying.basketball.domain.ShotClip
import com.feiying.basketball.domain.ShotEvent
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.ui.clip.ClipPlayerContent
import com.feiying.basketball.ui.result.ResultContent
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
 * Renders the result screen and the clip player at the same viewport, theme and
 * data used by the before/after evidence in the pull request.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp-xhdpi",
    application = ScreenshotApplication::class,
)
class ClipScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resultWithoutClips() {
        setResultContent(recordWithoutClips())
        capture("result-without-clips.png")
    }

    @Test
    fun resultWithClips() {
        setResultContent(recordWithClips())
        capture("result-with-clips.png")
    }

    @Test
    fun clipPlayer() {
        compose.setContent {
            BasketballTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ClipPlayerContent(
                        shotNumber = 2,
                        shotTime = "00:12",
                        rangeText = stringResource(R.string.clip_range, "00:07", "00:17"),
                        clipUrl = null,
                        saving = false,
                        onBack = {},
                        onSave = {},
                        playerSlot = { modifier ->
                            Box(
                                modifier = modifier.background(Color.Black),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = Color.White,
                                )
                            }
                        },
                    )
                }
            }
        }
        capture("clip-player.png")
    }

    private fun setResultContent(record: AnalysisRecord) {
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
    }

    private fun recordWithoutClips(): AnalysisRecord {
        val now = 1_760_000_000_000L
        return AnalysisRecord(
            localId = "record-clips",
            serverJobId = "job-clips",
            createdAt = now,
            source = VideoSource.GALLERY,
            localVideoPath = null,
            filename = "night-game.mp4",
            sizeBytes = 42L * 1024 * 1024,
            durationMs = 95_000,
            status = AnalysisStatus.COMPLETED,
            result = AnalysisResult(
                detector = "classical",
                shots = listOf(
                    ShotEvent(4.0, 120, true),
                    ShotEvent(12.0, 360, true),
                    ShotEvent(21.0, 630, false),
                ),
            ),
            updatedAt = now,
        )
    }

    private fun recordWithClips(): AnalysisRecord {
        val base = recordWithoutClips()
        val clip0 = ShotClip(
            status = ClipStatus.READY,
            startSeconds = 0.0,
            endSeconds = 9.0,
            durationSeconds = 9.0,
            sizeBytes = 2_400_000,
            url = "/api/v1/analyses/job-clips/clips/0",
        )
        val clip1 = ShotClip(
            status = ClipStatus.READY,
            startSeconds = 7.0,
            endSeconds = 17.0,
            durationSeconds = 10.0,
            sizeBytes = 2_800_000,
            url = "/api/v1/analyses/job-clips/clips/1",
        )
        return base.copy(
            result = base.result?.copy(
                shots = listOf(
                    base.result.shots[0].copy(clip = clip0),
                    base.result.shots[1].copy(clip = clip1),
                    base.result.shots[2],
                ),
            ),
        )
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
}
