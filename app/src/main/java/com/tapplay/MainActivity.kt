package com.tapplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tapplay.ui.AppTypography
import com.tapplay.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private val playbackManager by lazy {
        (application as TapPlayApplication).playbackManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        playbackManager.connect()
        playbackManager.restoreLastSession()
        setContent {
            MaterialTheme(
                colors = darkColors(surface = Color(0xFF1A1A1A)),
                typography = AppTypography,
            ) {
                PlayerScreen()
            }
        }
    }

    override fun onDestroy() {
        playbackManager.disconnect()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
