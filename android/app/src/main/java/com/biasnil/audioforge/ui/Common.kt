package com.biasnil.audioforge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.biasnil.audioforge.AppContainer
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.formatTime
import kotlinx.coroutines.delay

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** "3 tracks" / "1 track". */
@Composable
fun trackCountText(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.track_count, count, count)

/** "Artist  •  Album", with the desktop's "Unknown Artist" fallback. */
@Composable
fun trackSubtitle(track: Track): String {
    val artist = track.artist.ifEmpty { stringResource(R.string.unknown_artist) }
    return if (track.album.isEmpty()) artist else "$artist  •  ${track.album}"
}

/** One song in a list. Long-press is "edit tags" wherever [onLongClick] is given. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable, on older Compose versions
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
        },
        supportingContent = {
            Text(trackSubtitle(track), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (trailing != null) {
                trailing()
            } else {
                Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelMedium)
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/** Cover art decoded at [decodeSize]; a music-note placeholder while loading or if there's none. */
@Composable
fun CoverImage(
    track: Track,
    decodeSize: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    val bitmap by rememberCoverArt(track, decodeSize)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap?.let { remember(it) { it.asImageBitmap() } }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop, // fill and crop, like the desktop's ScaledCoverArt()
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
    }
}

@Composable
fun rememberCoverArt(track: Track, decodeSize: Dp): State<Bitmap?> {
    val loader = LocalAppContainer.current.coverArt
    val sizePx = with(LocalDensity.current) { decodeSize.roundToPx() }
    return produceState(initialValue = loader.cached(track, sizePx), track.uri, sizePx) {
        value = loader.load(track, sizePx)
    }
}

/** The player's position, re-read 4x a second while the app is visible. */
@Composable
fun rememberPlaybackPositionMs(): State<Long> {
    val playback = LocalAppContainer.current.playback
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(initialValue = playback.positionMs(), playback, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = playback.positionMs()
                delay(250)
            }
        }
    }
}

/** Averages a 24x24 downscale, darkened so white text stays readable -- the desktop's AverageColor(). */
fun Bitmap.averageColor(): Color {
    val size = 24
    val small = Bitmap.createScaledBitmap(this, size, size, false)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)
    var red = 0L
    var green = 0L
    var blue = 0L
    for (pixel in pixels) {
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    val count = pixels.size
    return Color(
        red = (red / count * 0.55).toInt(),
        green = (green / count * 0.55).toInt(),
        blue = (blue / count * 0.55).toInt(),
    )
}

/**
 * Shown instead of an empty list: scanning, missing permission, or no music
 * found -- each with the one action that fixes it.
 */
@Composable
fun LibraryEmptyState(onRequestAudioPermission: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val hasPermission by container.library.hasAudioPermission.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            scanning -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                CenteredText(stringResource(R.string.library_scanning))
            }
            settings.includePhoneLibrary && !hasPermission -> {
                CenteredText(stringResource(R.string.library_needs_permission))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequestAudioPermission) { Text(stringResource(R.string.allow_access)) }
            }
            else -> {
                CenteredText(stringResource(R.string.library_empty))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = container.library::refresh) { Text(stringResource(R.string.refresh_library)) }
            }
        }
    }
}

@Composable
fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
