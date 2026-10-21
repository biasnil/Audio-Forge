package com.biasnil.audioforge.ui

import android.graphics.Bitmap
import com.biasnil.audioforge.data.Track

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.SyncedLyricLine
import com.biasnil.audioforge.lyrics.currentLyricIndex
import com.biasnil.audioforge.playback.AbRepeat
import com.biasnil.audioforge.playback.PlaybackState
import com.biasnil.audioforge.playback.RepeatMode
import com.biasnil.audioforge.playback.ShuffleMode
import com.biasnil.audioforge.playback.SleepTimer
import com.biasnil.audioforge.ui.theme.AudioForgeTheme
import com.biasnil.audioforge.ui.theme.isDark
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60)

/**
 * The full-screen player, laid out like the iOS app's: Love and Up next
 * on top, the cover (long-press for the song menu), title, seek bar,
 * transport, volume, and a row of extras (speed, lyrics, A-B repeat,
 * sleep timer). The background is the blurred cover tinted with its colour
 * (or the song's video wallpaper), and the controls take the cover's colour.
 */
@OptIn(ExperimentalFoundationApi::class) // basicMarquee, combinedClickable on older Compose versions
@Composable
fun NowPlayingScreen(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val playback = container.playback
    val trackActions = LocalTrackActions.current
    val state by playback.state.collectAsStateWithLifecycle()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val stats by container.songData.stats.collectAsStateWithLifecycle()
    val lyrics by container.lyrics.state.collectAsStateWithLifecycle()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val cover by rememberCoverArt(track, decodeSize = 300.dp)
    val themeColor = remember(cover) { cover?.themeColor() }

    AudioForgeTheme(darkTheme = isDark(settings.appearance), accent = themeColor) {
        val position by rememberPlaybackPositionMs()
        var showLyrics by rememberSaveable { mutableStateOf(false) }
        var showVisualizer by rememberSaveable { mutableStateOf(false) }
        val lyricsAvailable = lyrics is LyricsState.Plain || lyrics is LyricsState.Synced
        val lyricsMode = showLyrics && lyricsAvailable

        // Live video wallpaper: the song's own video, else the global one (see WallpaperRepository).
        val videoUri = if (settings.videoWallpaperEnabled) container.wallpapers.resolveVideoFor(track.uri, settings) else null
        var videoFailed by remember(videoUri) { mutableStateOf(false) }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (videoUri != null && !videoFailed) {
                VideoWallpaper(
                    videoUri = videoUri,
                    opacity = settings.videoWallpaperOpacityPercent / 100f,
                    onFailed = { videoFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
                )
            } else {
                cover?.let { bitmap ->
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(48.dp)
                            .alpha(0.35f),
                    )
                }
                if (themeColor != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(themeColor.copy(alpha = 0.35f), Color.Transparent)))
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TopBar(
                    loved = stats[track.uri]?.favorite == true,
                    onToggleLove = { container.songData.toggleFavorite(track.uri) },
                    onBack = onBack,
                    onUpNext = { onOpenPage(Page.UpNext) },
                    onFullLyrics = if (lyricsMode) ({ onOpenPage(Page.FullLyrics) }) else null,
                )

                if (lyricsMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(track, decodeSize = 56.dp, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        TitleBlock(track.title, track.artist, large = false, modifier = Modifier.weight(1f))
                    }
                    LyricsContent(
                        lyrics = lyrics,
                        positionMs = position,
                        offsetMs = stats[track.uri]?.lyricsOffsetMs ?: 0L,
                        onSeek = playback::seekTo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        val side: Dp = min(min(maxWidth, maxHeight), 340.dp)
                        if (showVisualizer) {
                            NowPlayingVisualizer(track, themeColor ?: MaterialTheme.colorScheme.primary, cover, Modifier.size(side))
                        } else {
                            CoverImage(
                                track = track,
                                decodeSize = 300.dp,
                                cornerRadius = 10.dp,
                                modifier = Modifier
                                    .size(side)
                                    .shadow(24.dp, RoundedCornerShape(10.dp), spotColor = themeColor ?: Color.Black)
                                    .combinedClickable(onClick = {}, onLongClick = { trackActions.showMenu(track) }),
                            )
                        }
                    }
                    TitleBlock(track.title, track.artist, large = true, modifier = Modifier.fillMaxWidth())
                }

                ProgressBar(state, position, onSeek = playback::seekTo)
                if (state.isLongTrack) SkipButtons(onSkip = playback::skipBy)
                Controls(state, playback::cycleShuffleMode, playback::previous, playback::togglePlayPause, playback::next, playback::cycleRepeatMode)
                VolumeRow(state.volumePercent, playback::setVolume, playback::saveVolume)
                Extras(
                    state = state,
                    lyricsAvailable = lyricsAvailable,
                    lyricsLoading = lyrics == LyricsState.Loading,
                    lyricsMode = lyricsMode,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    visualizerOn = showVisualizer,
                    onToggleVisualizer = { showVisualizer = !showVisualizer },
                    onRate = playback::setRate,
                    onAbRepeat = playback::cycleAbRepeat,
                    onSleepTimer = playback::setSleepTimer,
                )
            }
        }
    }
}

/** Back and Love on the left; full-screen lyrics (in lyrics mode) and Up next on the right. */
@Composable
private fun TopBar(
    loved: Boolean,
    onToggleLove: () -> Unit,
    onBack: () -> Unit,
    onUpNext: () -> Unit,
    onFullLyrics: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.Back, contentDescription = stringResource(R.string.back))
        }
        IconButton(onClick = onToggleLove) {
            Icon(
                imageVector = if (loved) AppIcons.Heart else AppIcons.HeartOutline,
                contentDescription = stringResource(if (loved) R.string.unlove else R.string.love),
                tint = if (loved) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (onFullLyrics != null) {
            IconButton(onClick = onFullLyrics) {
                Icon(AppIcons.Fullscreen, contentDescription = stringResource(R.string.lyrics_full_screen))
            }
        }
        IconButton(onClick = onUpNext) {
            Icon(AppIcons.ListBullet, contentDescription = stringResource(R.string.up_next))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(title: String, artist: String, large: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = if (large) Alignment.CenterHorizontally else Alignment.Start) {
        // Long titles scroll instead of wrapping (the desktop's title marquee).
        Text(
            text = title,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        Text(
            text = artist.ifEmpty { stringResource(R.string.unknown_artist) },
            style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Seek bar with elapsed and "-remaining" times; it seeks once, when you let go. */
@Composable
private fun ProgressBar(state: PlaybackState, position: Long, onSeek: (Long) -> Unit) {
    var dragPositionMs by remember { mutableStateOf<Float?>(null) }
    val shown = dragPositionMs?.toLong() ?: position
    val duration = state.durationMs.toFloat().coerceAtLeast(1f)
    Column {
        Slider(
            value = shown.toFloat().coerceIn(0f, duration),
            onValueChange = { dragPositionMs = it },
            onValueChangeFinished = {
                dragPositionMs?.let { onSeek(it.toLong()) }
                dragPositionMs = null
            },
            valueRange = 0f..duration,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            ),
            modifier = Modifier.height(28.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "-" + formatTime((state.durationMs - shown).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ±15 s for long tracks (audiobooks, mixes). */
@Composable
private fun SkipButtons(onSkip: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)) {
        SkipButton(AppIcons.Replay, stringResource(R.string.back_15)) { onSkip(-15_000) }
        SkipButton(AppIcons.Forward, stringResource(R.string.forward_15)) { onSkip(15_000) }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
            Text("15", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleLabel = stringResource(
            when (state.shuffleMode) {
                ShuffleMode.Off -> R.string.mode_off
                ShuffleMode.Random -> R.string.shuffle_random
                ShuffleMode.Smart -> R.string.shuffle_smart
            }
        )
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onShuffle)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val color = if (state.shuffleMode == ShuffleMode.Off) muted else accent
            Icon(AppIcons.Shuffle, contentDescription = stringResource(R.string.shuffle) + ": " + shuffleLabel, tint = color)
            Text(shuffleLabel, style = MaterialTheme.typography.labelSmall, color = color)
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(AppIcons.FastRewind, stringResource(R.string.previous), Modifier.size(40.dp))
        }
        // White circle with a dark symbol, like play.circle.fill.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(AppIcons.FastForward, stringResource(R.string.next), Modifier.size(40.dp))
        }
        val repeatLabel = stringResource(
            when (state.repeatMode) {
                RepeatMode.Off -> R.string.mode_off
                RepeatMode.All -> R.string.repeat_all
                RepeatMode.One -> R.string.repeat_one
            }
        )
        IconButton(onClick = onRepeat) {
            Icon(
                imageVector = if (state.repeatMode == RepeatMode.One) AppIcons.RepeatOne else AppIcons.Repeat,
                contentDescription = stringResource(R.string.repeat) + ": " + repeatLabel,
                tint = if (state.repeatMode == RepeatMode.Off) muted else accent,
            )
        }
    }
}

/** The app's own volume, up to 200% (above 100% boosts, and the bar turns orange). */
@Composable
private fun VolumeRow(volumePercent: Int, onVolume: (Int) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Speaker, contentDescription = stringResource(R.string.volume), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { onVolume(it.roundToInt()) },
            onValueChangeFinished = onDone,
            valueRange = 0f..AppSettings.MAX_VOLUME_PERCENT.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = if (volumePercent > 100) Color(0xFFFF9500) else MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            ),
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$volumePercent%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/** Speed, lyrics, A-B repeat, sleep timer. */
@Composable
private fun Extras(
    state: PlaybackState,
    lyricsAvailable: Boolean,
    lyricsLoading: Boolean,
    lyricsMode: Boolean,
    onToggleLyrics: () -> Unit,
    visualizerOn: Boolean,
    onToggleVisualizer: () -> Unit,
    onRate: (Float) -> Unit,
    onAbRepeat: () -> Unit,
    onSleepTimer: (SleepTimer) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Speed
        var speedMenu by remember { mutableStateOf(false) }
        Box {
            Text(
                text = rateText(state.rate),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .clickable { speedMenu = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                RATES.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text(rateText(rate), color = if (rate == state.rate) accent else Color.Unspecified) },
                        onClick = {
                            speedMenu = false
                            onRate(rate)
                        },
                    )
                }
            }
        }

        // Lyrics
        IconButton(onClick = onToggleLyrics, enabled = lyricsAvailable) {
            Icon(
                imageVector = AppIcons.Lyrics,
                contentDescription = stringResource(R.string.show_lyrics),
                tint = when {
                    lyricsMode -> accent
                    lyricsAvailable -> normal
                    lyricsLoading -> normal.copy(alpha = 0.6f)
                    else -> normal.copy(alpha = 0.3f)
                },
            )
        }

        // Visualizer (instead of the cover; not in lyrics mode)
        IconButton(onClick = onToggleVisualizer, enabled = !lyricsMode) {
            Icon(
                imageVector = AppIcons.Waveform,
                contentDescription = stringResource(R.string.visualizer),
                tint = when {
                    lyricsMode -> normal.copy(alpha = 0.3f)
                    visualizerOn -> accent
                    else -> normal
                },
            )
        }

        // A-B repeat
        val ab = state.abRepeat
        Text(
            text = if (ab is AbRepeat.ASet) "A–" else "A–B",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = when (ab) {
                AbRepeat.Off -> normal
                is AbRepeat.ASet -> accent
                is AbRepeat.Looping -> Color.White
            },
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .then(if (ab is AbRepeat.Looping) Modifier.background(accent) else Modifier)
                .clickable(onClick = onAbRepeat)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // Sleep timer
        var sleepMenu by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { sleepMenu = true }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val on = state.sleepTimer != SleepTimer.Off
                Icon(
                    imageVector = AppIcons.Moon,
                    contentDescription = stringResource(R.string.sleep_timer),
                    tint = if (on) accent else normal,
                )
                state.sleepEndsAtMs?.let { end ->
                    Spacer(Modifier.width(4.dp))
                    SleepCountdown(end)
                }
            }
            DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                val pick: (SleepTimer) -> Unit = {
                    sleepMenu = false
                    onSleepTimer(it)
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mode_off), color = if (state.sleepTimer == SleepTimer.Off) accent else Color.Unspecified) },
                    onClick = { pick(SleepTimer.Off) },
                )
                SLEEP_MINUTES.forEach { minutes ->
                    val selected = state.sleepTimer == SleepTimer.Minutes(minutes)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sleep_minutes, minutes), color = if (selected) accent else Color.Unspecified) },
                        onClick = { pick(SleepTimer.Minutes(minutes)) },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.sleep_end_of_song),
                            color = if (state.sleepTimer == SleepTimer.EndOfTrack) accent else Color.Unspecified,
                        )
                    },
                    onClick = { pick(SleepTimer.EndOfTrack) },
                )
            }
        }
    }
}

/** "12:34" left on the sleep timer, ticking. */
@Composable
private fun SleepCountdown(endsAtMs: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        text = formatTime(endsAtMs - now),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun rateText(rate: Float): String =
    (if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString().trimEnd('0')) + "×"

/**
 * The visualizer in place of the cover: the song's own GIF / video (or the
 * global one) if it has one, else the style chosen in Settings, in the
 * cover's colour. Its own composable, so ~30 frames a second only redraw this.
 */
@Composable
private fun NowPlayingVisualizer(track: Track, color: Color, cover: Bitmap?, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val frame by rememberVisualizerFrame(active = true)
    val media = container.customMedia.resolveFor(track.uri, settings)
    var mediaFailed by remember(media?.uri) { mutableStateOf(false) }
    if (media != null && !mediaFailed) {
        CustomMediaView(media, frame.spinDegrees, onFailed = { mediaFailed = true }, modifier = modifier)
    } else {
        val image = remember(cover) { cover?.asImageBitmap() }
        VisualizerView(settings.visualizerStyle, frame, color, image, modifier)
    }
}
