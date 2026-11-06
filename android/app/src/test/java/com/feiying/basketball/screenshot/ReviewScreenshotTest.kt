package com.feiying.basketball.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.feiying.basketball.domain.HoopRoi
import com.feiying.basketball.domain.SelectedVideo
import com.feiying.basketball.domain.VideoSource
import com.feiying.basketball.ui.review.HoopRoiPickerContent
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
 * Renders the review screen and the manual hoop picker at the same viewport,
 * theme and state as the real app. The generated PNGs are the PR's visual
 * evidence for the "user draws the hoop" feature.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp-xhdpi",
    application = ScreenshotApplication::class,
)
class ReviewScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reviewWithHoopRoiEntry() {
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
                        hoopRoi = null,
                        onBack = {},
                        onStart = {},
                        onReselect = {},
                        onRerecord = {},
                    )
                }
            }
        }
        capture("review-hoop-entry.png")
    }

    @Test
    fun hoopRoiPickerSelected() {
        compose.setContent {
            BasketballTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HoopRoiPickerContent(
                        bitmap = courtFrame(),
                        initial = HoopRoi(0.43f, 0.27f, 0.14f, 0.09f),
                        onCancel = {},
                        onConfirm = {},
                    )
                }
            }
        }
        capture("hoop-roi-picker.png")
    }

    private fun courtFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        canvas.drawColor(Color.rgb(18, 22, 30))
        // Court floor.
        paint.color = Color.rgb(58, 46, 34)
        canvas.drawRect(0f, 520f, 1280f, 720f, paint)
        // Backboard and rim.
        paint.color = Color.rgb(210, 210, 205)
        canvas.drawRect(560f, 120f, 720f, 300f, paint)
        paint.color = Color.rgb(238, 110, 36)
        canvas.drawRect(590f, 300f, 690f, 316f, paint)
        // Players.
        paint.color = Color.rgb(30, 34, 44)
        canvas.drawCircle(300f, 470f, 55f, paint)
        canvas.drawRect(240f, 500f, 360f, 660f, paint)
        canvas.drawCircle(980f, 460f, 50f, paint)
        canvas.drawRect(925f, 495f, 1035f, 660f, paint)
        return bitmap
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
