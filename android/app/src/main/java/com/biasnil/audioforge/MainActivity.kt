package com.biasnil.audioforge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.biasnil.audioforge.linkdownload.LinkDownloadFeature
import com.biasnil.audioforge.playback.PlaybackService
import com.biasnil.audioforge.ui.AudioForgeApp
import com.biasnil.audioforge.ui.theme.AudioForgeTheme
import com.biasnil.audioforge.ui.theme.isDark
import com.biasnil.audioforge.ui.theme.themeStyle
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as AudioForgeApplication).container }
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is mandatory when targeting Android 15+; the Scaffolds
        // pad content away from the status/navigation bars.
        enableEdgeToEdge(statusBarStyle = barStyle(darkTheme = true), navigationBarStyle = barStyle(darkTheme = true))
        super.onCreate(savedInstanceState)

        setContent {
            val settings by container.store.settings.collectAsStateWithLifecycle()
            val darkTheme = isDark(settings.appearance)

            // The app's theme is independent of the phone's, so the system bar
            // icons (clock, battery...) have to be switched to match it.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(statusBarStyle = barStyle(darkTheme), navigationBarStyle = barStyle(darkTheme))
            }

            AudioForgeTheme(darkTheme = darkTheme, style = settings.themeStyle) {
                AudioForgeApp(container)
            }
        }
        if (savedInstanceState == null) shareLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shareLink(intent)
    }

    /** A link shared to the app ("Share" -> AudioForge, GitHub version only) goes to Download from link. */
    private fun shareLink(intent: Intent?) {
        if (!LinkDownloadFeature.AVAILABLE || intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = LINK.find(text)?.value ?: return
        container.sharedLink.value = link
    }

    override fun onStart() {
        super.onStart()
        container.appVisible.value = true
        // Connecting a controller starts PlaybackService, which publishes the
        // media session (notification, lock screen, headphone buttons). The
        // UI itself talks to PlaybackManager directly, so the controller isn't
        // otherwise used.
        controllerFuture = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
        ).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        // Music access can be granted or revoked in system settings while we're in the background.
        container.library.onAppResumed()
    }

    override fun onStop() {
        container.appVisible.value = false
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun barStyle(darkTheme: Boolean): SystemBarStyle =
        if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
}

/** The first web link in shared text (apps often share "Title https://..."). */
private val LINK = Regex("https?://\\S+")
