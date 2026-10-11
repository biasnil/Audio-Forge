package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biasnil.audioforge.R
import com.biasnil.audioforge.playback.PlaybackState

/**
 * The iOS app's compact floating player: cover, title, play/pause and next,
 * with a thin progress line. Tap or swipe up for Now Playing.
 */
@Composable
fun MiniPlayerBar(state: PlaybackState, onOpenNowPlaying: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    val playback = LocalAppContainer.current.playback
    val position by rememberPlaybackPositionMs()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNowPlaying)
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (dragged < -30f) onOpenNowPlaying() },
                            onVerticalDrag = { _, amount -> dragged += amount },
                        )
                    }
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(track, decodeSize = 40.dp, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist.ifEmpty { stringResource(R.string.unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = playback::togglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    )
                }
                IconButton(onClick = playback::next) {
                    Icon(AppIcons.FastForward, contentDescription = stringResource(R.string.next))
                }
            }
            // Thin progress line along the bottom edge.
            val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .height(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * The iOS-style floating tab bar: a rounded pill with icon + label per
 * tab, the selected one highlighted.
 */
@Composable
fun AppTabBar(
    items: List<Pair<String, LibraryTab?>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(4.dp)) {
            items.forEach { (key, tab) ->
                val isSelected = key == selected
                val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { onSelect(key) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = tab?.icon ?: AppIcons.MoreHoriz,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(tab?.title ?: R.string.tab_more),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
