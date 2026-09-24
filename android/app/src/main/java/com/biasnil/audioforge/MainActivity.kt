package com.biasnil.audioforge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.biasnil.audioforge.ui.AudioForgeApp
import com.biasnil.audioforge.ui.theme.AudioForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is mandatory when targeting Android 15+; the Scaffold
        // in AudioForgeApp pads content away from the status/navigation bars.
        enableEdgeToEdge(statusBarStyle = barStyle(darkTheme = true), navigationBarStyle = barStyle(darkTheme = true))
        super.onCreate(savedInstanceState)

        setContent {
            // Dark by default, same as the desktop app. Kept only for this
            // session for now -- saved with the rest of the settings in stage 2.
            var darkTheme by rememberSaveable { mutableStateOf(true) }

            // The app's theme is independent of the phone's, so the system bar
            // icons (clock, battery...) have to be switched to match it.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(statusBarStyle = barStyle(darkTheme), navigationBarStyle = barStyle(darkTheme))
            }

            AudioForgeTheme(darkTheme = darkTheme) {
                AudioForgeApp(
                    darkTheme = darkTheme,
                    onToggleTheme = { darkTheme = !darkTheme },
                )
            }
        }
    }

    private fun barStyle(darkTheme: Boolean): SystemBarStyle =
        if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
}
