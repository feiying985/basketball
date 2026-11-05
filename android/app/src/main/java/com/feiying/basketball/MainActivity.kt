package com.feiying.basketball

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.feiying.basketball.ui.BasketballApp
import com.feiying.basketball.ui.theme.BasketballTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BasketballApplication).container
        setContent {
            BasketballTheme {
                BasketballApp(container = container)
            }
        }
    }
}
