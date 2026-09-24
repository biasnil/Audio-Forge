package com.biasnil.audioforge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.formatAudioInfo
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.playback.RepeatMode
import com.biasnil.audioforge.playback.ShuffleMode
import com.biasnil.audioforge.ui.theme.AudioForgeTheme
import kotlin.math.roundToInt

/**
 * The desktop's Now Playing page. Always dark, whatever the app theme --
 * like the desktop's info box, it sits on a background tinted from the
 * cover art, which only works with light text.
 */
@OptIn(ExperimentalFoundationApi::class) // basicMarquee, on older Compose versions
@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val playback = LocalAppContainer.current.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    LightSystemBarIcons()

    AudioForgeTheme(darkTheme = true) {
        val cover by rememberCoverArt(track, decodeSize = 360.dp)
        val tint = remember(cover) { cover?.averageColor() ?: FallbackTint }
        val position by rememberPlaybackPositionMs()
        var dragPositionMs by remember { mutableStateOf<Float?>(null) }
        val shownPositionMs = dragPositionMs?.toLong() ?: position

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background)))
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            TextButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.back))
            }
            Spacer(Modifier.height(16.dp))

            CoverImage(
                track = track,
                decodeSize = 360.dp,
                cornerRadius = 8.dp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(24.dp))

            // Long titles scroll instead of wrapping (the desktop's title marquee).
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )
            Text(
                text = trackSubtitle(track),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )
            val audioInfo = formatAudioInfo(track)
            if (audioInfo.isNotEmpty()) {
                Text(
                    text = audioInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Slider(
                value = shownPositionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { dragPositionMs = it },
                onValueChangeFinished = {
                    dragPositionMs?.let { playback.seekTo(it.toLong()) }
                    dragPositionMs = null
                },
                valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shownPositionMs), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeButton(
                    icon = AppIcons.Shuffle,
                    active = state.shuffleMode != ShuffleMode.Off,
                    label = stringResource(
                        when (state.shuffleMode) {
                            ShuffleMode.Off -> R.string.mode_off
                            ShuffleMode.Random -> R.string.shuffle_random
                            ShuffleMode.Smart -> R.string.shuffle_smart
                        }
                    ),
                    description = stringResource(R.string.shuffle),
                    onClick = playback::cycleShuffleMode,
                )
                IconButton(onClick = playback::previous, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
                }
                FilledIconButton(onClick = playback::togglePlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = playback::next, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
                }
                ModeButton(
                    icon = if (state.repeatMode == RepeatMode.One) AppIcons.RepeatOne else AppIcons.Repeat,
                    active = state.repeatMode != RepeatMode.Off,
                    label = stringResource(
                        when (state.repeatMode) {
                            RepeatMode.Off -> R.string.mode_off
                            RepeatMode.All -> R.string.repeat_all
                            RepeatMode.One -> R.string.repeat_one
                        }
                    ),
                    description = stringResource(R.string.repeat),
                    onClick = playback::cycleRepeatMode,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.volume), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = state.volumePercent.toFloat(),
                    onValueChange = { playback.setVolume(it.roundToInt()) },
                    onValueChangeFinished = playback::saveVolume,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Shuffle / Repeat: an icon lit in the accent color when on, with the mode under it ("Smart", "One"...). */
@Composable
private fun ModeButton(
    icon: ImageVector,
    active: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = "$description: $label",
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * This screen is dark in both app themes, so while it's showing the status
 * and navigation bar icons must be light; the previous setting comes back
 * when it closes.
 */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null && previousStatus != null && previousNavigation != null) {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The desktop's fallback tint for tracks without cover art. */
private val FallbackTint = Color(0xFF232428)
