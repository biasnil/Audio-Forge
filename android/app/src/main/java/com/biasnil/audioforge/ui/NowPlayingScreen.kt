package com.biasnil.audioforge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.formatAudioInfo
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.SyncedLyricLine
import com.biasnil.audioforge.lyrics.currentLyricIndex
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.playback.RepeatMode
import com.biasnil.audioforge.playback.ShuffleMode
import com.biasnil.audioforge.ui.theme.AudioForgeTheme
import kotlin.math.abs
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
        var showLyrics by rememberSaveable { mutableStateOf(false) }
        val shownPositionMs = dragPositionMs?.toLong() ?: position

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background)))
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Icon(AppIcons.Back, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Text(stringResource(if (showLyrics) R.string.show_cover else R.string.show_lyrics))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Cover art or lyrics, in the same square -- tap "Lyrics"/"Cover" to switch.
            val squareModifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
            if (showLyrics) {
                LyricsPanel(positionMs = position, onSeek = playback::seekTo, modifier = squareModifier)
            } else {
                CoverImage(track = track, decodeSize = 360.dp, cornerRadius = 8.dp, modifier = squareModifier)
            }
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

            // Up to 200%, like the desktop: above 100% boosts (and can clip loud tracks).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.volume), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = state.volumePercent.toFloat(),
                    onValueChange = { playback.setVolume(it.roundToInt()) },
                    onValueChangeFinished = playback::saveVolume,
                    valueRange = 0f..AppSettings.MAX_VOLUME_PERCENT.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${state.volumePercent}%",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The lyrics for the current track (see LyricsRepository). Synced lyrics
 * follow the music -- the current line large and bright, the rest fading
 * with distance -- and tapping a line jumps there, like the desktop.
 */
@Composable
private fun LyricsPanel(positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val lyrics by LocalAppContainer.current.lyrics.state.collectAsStateWithLifecycle()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = lyrics) {
            LyricsState.None -> Unit
            LyricsState.Loading -> CenteredText(stringResource(R.string.lyrics_loading))
            LyricsState.NotFound -> CenteredText(stringResource(R.string.lyrics_not_found))
            is LyricsState.Plain -> LazyColumn(Modifier.fillMaxSize()) {
                items(current.lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
            is LyricsState.Synced -> SyncedLyrics(current.lines, positionMs, onSeek)
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<SyncedLyricLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val currentIndex = currentLyricIndex(lines, positionMs)
    val listState = rememberLazyListState()
    // Keep the current line a couple of rows from the top, teleprompter-style.
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == currentIndex
            val distance = abs(index - currentIndex)
            Text(
                text = line.text.ifEmpty { "\u266A" }, // instrumental gap
                style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isCurrent) 1f else (0.8f - distance * 0.12f).coerceAtLeast(0.3f),
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 6.dp),
            )
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
