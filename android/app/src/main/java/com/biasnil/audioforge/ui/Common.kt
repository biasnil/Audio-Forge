package com.biasnil.audioforge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * One song in a list, like the iOS app's SongRow: cover, title, artist, a
 * lyrics mark if it has a lyrics file, and the length (or [trailing]).
 * Long-press opens the song menu wherever [onLongClick] is given.
 * [containerColor] + [shape] make it part of a grouped card (Up next).
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable, on older Compose versions
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
    showDivider: Boolean = true,
    /** Multi-select: a check circle in front (null = not selecting). */
    selected: Boolean? = null,
    /** Shown under the artist (Duplicates: where the file is). */
    extraLine: String? = null,
) {
    Column(
        modifier = modifier
            .then(if (containerColor != Color.Unspecified) Modifier.background(containerColor, shape) else Modifier)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Icon(
                    imageVector = if (selected) AppIcons.CheckCircle else AppIcons.Circle,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
            }
            CoverImage(track, decodeSize = 44.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = track.artist.ifEmpty { stringResource(R.string.unknown_artist) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (extraLine != null) {
                    Text(
                        text = extraLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (track.sidecarLyricsUri != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = AppIcons.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (trailing != null) {
                trailing()
            } else if (track.durationMs > 0) {
                Text(
                    text = formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDivider) {
            // Starts under the text, like iOS list separators.
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Rounded top on the first row of a grouped card, rounded bottom on the last. */
fun groupedShape(index: Int, count: Int, radius: Dp = 12.dp): Shape = RoundedCornerShape(
    topStart = if (index == 0) radius else 0.dp,
    topEnd = if (index == 0) radius else 0.dp,
    bottomStart = if (index == count - 1) radius else 0.dp,
    bottomEnd = if (index == count - 1) radius else 0.dp,
)

/** A tab's large title (iOS navigation title), with an optional back arrow and actions. */
@Composable
fun TabHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.back))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** A grey section title above a grouped card ("History", "Now Playing"...). */
@Composable
fun GroupedHeader(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action()
    }
}

/**
 * The A-Z strip down the side of the Songs list (the iOS AlphabetIndex):
 * tap or drag along it to jump.
 */
@Composable
fun AlphabetIndex(letters: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val rowHeight = 15.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val padPx = with(LocalDensity.current) { 4.dp.toPx() }
    var lastLetter by remember { mutableStateOf<String?>(null) }
    fun pick(y: Float) {
        if (letters.isEmpty()) return
        val letter = letters[((y - padPx) / rowPx).toInt().coerceIn(0, letters.lastIndex)]
        if (letter != lastLetter) {
            lastLetter = letter
            onSelect(letter)
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
            .padding(vertical = 4.dp)
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { pick(it.y) },
                    onDragEnd = { lastLetter = null },
                    onVerticalDrag = { change, _ -> pick(change.position.y) },
                )
            }
            .pointerInput(letters) {
                detectTapGestures { pick(it.y); lastLetter = null }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Box(Modifier.size(width = 18.dp, height = rowHeight), contentAlignment = Alignment.Center) {
                Text(
                    text = letter,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
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
    val version by loader.version.collectAsStateWithLifecycle() // reload after a cover change
    return produceState(initialValue = loader.cached(track, sizePx), track.uri, sizePx, version) {
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

/**
 * The cover's most characteristic colour (saturated pixels count more),
 * toned to work as a tint on light and dark backgrounds -- the iOS app's
 * ImageTools.themeColor().
 */
fun Bitmap.themeColor(): Color? {
    val size = 32
    val small = Bitmap.createScaledBitmap(this, size, size, true)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for (pixel in pixels) {
        val r = ((pixel shr 16) and 0xFF) / 255.0
        val g = ((pixel shr 8) and 0xFF) / 255.0
        val b = (pixel and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max > 0) (max - min) / max else 0.0
        val weight = saturation * saturation * max + 0.02
        red += r * weight
        green += g * weight
        blue += b * weight
        total += weight
    }
    if (total <= 0) return null
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red / total * 255).toInt(), (green / total * 255).toInt(), (blue / total * 255).toInt(), hsv,
    )
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.6f, 0.9f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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

/** The iOS-style Play / Shuffle pair under a cover. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onPlay, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
            Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play))
        }
        FilledTonalButton(onClick = onShuffle, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
            Icon(AppIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shuffle))
        }
    }
}
