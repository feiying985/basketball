package com.feiying.basketball

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.feiying.basketball.ui.BasketballApp
import com.feiying.basketball.ui.theme.BasketballTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always black; keep white system-bar icons even when the system is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as BasketballApplication).container
        setContent {
            BasketballTheme {
                BasketballApp(container = container)
            }
        }
    }
}
