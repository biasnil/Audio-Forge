package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.lyrics.LYRICS_LEAD_MS
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.SyncedLyricLine
import com.biasnil.audioforge.lyrics.currentLyricIndex
import java.util.Locale

/**
 * Scrolling, auto-following lyrics (the iOS LyricsView): the current line
 * bright and centred, the rest dimmed; word-timed lyrics light up word by
 * word. Tap a line to jump to it. [positionMs] already has the song's
 * lyrics offset taken off; [onSeek] gets the line's own time.
 */
@Composable
fun SyncedLyricsView(
    lines: List<SyncedLyricLine>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val currentIndex = currentLyricIndex(lines, positionMs)
    val listState = rememberLazyListState()
    // Keep the current line around the middle, like the iOS app.
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(currentIndex, scrollOffset = -(viewport / 3))
        }
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val bright = MaterialTheme.colorScheme.onSurface
    val now = positionMs + LYRICS_LEAD_MS
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = if (large) 160.dp else 80.dp),
        verticalArrangement = Arrangement.spacedBy(if (large) 22.dp else 14.dp),
        modifier = modifier
            .fillMaxSize()
            .fadeEdges(),
    ) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == currentIndex
            val text = if (isCurrent && line.words.isNotEmpty()) {
                buildAnnotatedString {
                    line.words.forEach { word ->
                        withStyle(SpanStyle(color = if (word.timeMs <= now) bright else dim)) { append(word.text) }
                    }
                }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (isCurrent) bright else dim)) { append(line.text.ifEmpty { "♪" }) }
                }
            }
            Text(
                text = text,
                style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/** Unsynced lyrics: just scrollable text. */
@Composable
fun PlainLyricsView(lines: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(lines) { line ->
            Text(line, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Lyrics for Now Playing's lyrics mode (and the full-screen page). */
@Composable
fun LyricsContent(
    lyrics: LyricsState,
    positionMs: Long,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (lyrics) {
            LyricsState.None -> Unit
            LyricsState.Loading -> CenteredText(stringResource(R.string.lyrics_loading))
            LyricsState.NotFound -> CenteredText(stringResource(R.string.lyrics_not_found))
            is LyricsState.Plain -> PlainLyricsView(lyrics.lines)
            // "+" offset shows lines later: the lyrics run behind the music by that much.
            is LyricsState.Synced -> SyncedLyricsView(
                lines = lyrics.lines,
                positionMs = positionMs - offsetMs,
                onSeek = { lineTime -> onSeek((lineTime + offsetMs).coerceAtLeast(0)) },
                large = large,
            )
        }
    }
}

/** Fades the top and bottom 12% of a scrolling list (the iOS lyrics' mask). */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Lyrics filling the screen (the iOS FullScreenLyricsView), with a timing
 * adjustment for files that are slightly off: Earlier / Later in half
 * seconds, remembered per song.
 */
@Composable
fun FullLyricsPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val playback = container.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val lyrics by container.lyrics.state.collectAsStateWithLifecycle()
    val stats by container.songData.stats.collectAsStateWithLifecycle()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val position by rememberPlaybackPositionMs()
    val offsetMs = stats[track.uri]?.lyricsOffsetMs ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    track.artist.ifEmpty { stringResource(R.string.unknown_artist) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onBack) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.close))
            }
        }
        LyricsContent(
            lyrics = lyrics,
            positionMs = position,
            offsetMs = offsetMs,
            onSeek = playback::seekTo,
            large = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        if (lyrics is LyricsState.Synced) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { container.songData.setLyricsOffset(track.uri, offsetMs - 500) }) {
                    Text(stringResource(R.string.lyrics_earlier))
                }
                Text(
                    text = if (offsetMs == 0L) stringResource(R.string.lyrics_in_sync)
                    else String.format(Locale.ROOT, "%+.1f s", offsetMs / 1000.0),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.width(96.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = { container.songData.setLyricsOffset(track.uri, offsetMs + 500) }) {
                    Text(stringResource(R.string.lyrics_later))
                }
                if (offsetMs != 0L) {
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { container.songData.setLyricsOffset(track.uri, 0) }) {
                        Text(stringResource(R.string.lyrics_reset))
                    }
                }
            }
        }
    }
}
