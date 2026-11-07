package com.feiying.basketball.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.feiying.basketball.data.AppContainer
import com.feiying.basketball.ui.auth.LoginScreen
import com.feiying.basketball.ui.auth.RegisterScreen
import com.feiying.basketball.ui.auth.ResetPasswordScreen
import com.feiying.basketball.ui.theme.BasketballTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp-xhdpi",
    application = ScreenshotApplication::class,
)
class AuthScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container: AppContainer by lazy {
        AppContainer(compose.activity.applicationContext)
    }

    @Test
    fun loginScreen() {
        compose.setContent {
            BasketballTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LoginScreen(container = container, onRegister = {}, onReset = {})
                }
            }
        }
        capture("auth-login.png")
    }

    @Test
    fun registerScreen() {
        compose.setContent {
            BasketballTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RegisterScreen(container = container, onBack = {})
                }
            }
        }
        capture("auth-register.png")
    }

    @Test
    fun resetPasswordScreen() {
        compose.setContent {
            BasketballTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ResetPasswordScreen(container = container, onBack = {})
                }
            }
        }
        capture("auth-reset.png")
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
