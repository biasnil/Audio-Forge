package com.biasnil.audioforge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.CustomMediaEntry
import com.biasnil.audioforge.data.VisualizerStyle

/** What the custom-media picker accepts: GIFs (and animated WebP) and videos. */
private val MEDIA_TYPES = arrayOf("image/gif", "image/webp", "video/*")

/**
 * Settings -> Visualizer: the style shown in place of the cover when the
 * visualizer is on (a grid of live previews, moving with the song that's
 * playing), and the custom GIFs / videos that replace the style for their songs.
 */
@Composable
fun VisualizerSettings(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()

    Text(
        text = stringResource(R.string.visualizer_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    StyleGrid(
        selected = settings.visualizerStyle,
        onSelect = { style -> container.store.update { it.copy(visualizerStyle = style) } },
    )
    CustomMediaSettings(onOpenPage)
}

/** Every style as a small live preview, three to a row; the chosen one outlined. */
@Composable
private fun StyleGrid(selected: VisualizerStyle, onSelect: (VisualizerStyle) -> Unit) {
    val container = LocalAppContainer.current
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val current = playback.current
    val cover = if (current != null) rememberCoverArt(current, decodeSize = 120.dp).value else null
    val coverImage = remember(cover) { cover?.asImageBitmap() }
    val color = remember(cover) { cover?.themeColor() } ?: MaterialTheme.colorScheme.primary
    val frame by rememberVisualizerFrame(active = true, idleMotion = true)

    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VisualizerStyle.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { style ->
                    val chosen = style == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(style) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0B0B0F))
                                .border(
                                    width = if (chosen) 3.dp else 1.dp,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp),
                                )
                        ) {
                            VisualizerView(style, frame, color, coverImage, Modifier.fillMaxSize().padding(8.dp))
                        }
                        Text(
                            text = stringResource(style.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                // Keep the last row's tiles the same size.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val VisualizerStyle.label: Int
    get() = when (this) {
        VisualizerStyle.ClassicBars -> R.string.visualizer_classic_bars
        VisualizerStyle.Spectrum -> R.string.visualizer_spectrum
        VisualizerStyle.NeonBars -> R.string.visualizer_neon_bars
        VisualizerStyle.LineWave -> R.string.visualizer_line_wave
        VisualizerStyle.RetroWave -> R.string.visualizer_retro_wave
        VisualizerStyle.FlowingWave -> R.string.visualizer_flowing_wave
        VisualizerStyle.Radial -> R.string.visualizer_radial
        VisualizerStyle.Particles -> R.string.visualizer_particles
        VisualizerStyle.Geometric -> R.string.visualizer_geometric
        VisualizerStyle.Disc -> R.string.visualizer_disc
    }

/** The global GIF / video, and the ones assigned to chosen songs. */
@Composable
private fun CustomMediaSettings(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val media = container.customMedia
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val global = settings.customMedia.firstOrNull { it.global }
    val perSong = settings.customMedia.filter { !it.global }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    val pickGlobal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) media.setGlobal(uri)
    }
    // New item for songs: pick the file, then the songs (it's only saved once songs are chosen).
    val pickForSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenPage(Page.MediaTracks(entryId = null, newUri = uri.toString()))
    }

    Text(
        text = stringResource(R.string.custom_media),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    Text(
        text = stringResource(R.string.custom_media_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    // For every song.
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.custom_media_global), style = MaterialTheme.typography.labelLarge)
        Text(
            text = global?.let { media.displayName(it.uri) } ?: stringResource(R.string.wallpaper_none),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (global != null) MediaOptions(global)
        Row {
            TextButton(onClick = { pickGlobal.launch(MEDIA_TYPES) }) { Text(stringResource(R.string.custom_media_choose)) }
            if (global != null) TextButton(onClick = { media.remove(global.id) }) { Text(stringResource(R.string.wallpaper_clear)) }
        }
    }

    // For chosen songs.
    perSong.forEach { entry ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(media.displayName(entry.uri), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = trackCountText(entry.trackUris.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenPage(Page.MediaTracks(entryId = entry.id, newUri = null)) }) {
                    Text(stringResource(R.string.wallpaper_edit_songs))
                }
                TextButton(onClick = { removingId = entry.id }) { Text(stringResource(R.string.remove)) }
            }
            MediaOptions(entry)
        }
    }
    Button(
        onClick = { pickForSongs.launch(MEDIA_TYPES) },
        modifier = Modifier.padding(16.dp),
    ) {
        Icon(AppIcons.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.custom_media_add))
    }

    removingId?.let { id ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.remove)) },
            text = { Text(stringResource(R.string.custom_media_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    media.remove(id)
                    removingId = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Round or square, and whether it spins while music plays. */
@Composable
private fun MediaOptions(entry: CustomMediaEntry) {
    val media = LocalAppContainer.current.customMedia
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            listOf(true to R.string.custom_media_round, false to R.string.custom_media_square).forEachIndexed { index, (round, label) ->
                SegmentedButton(
                    selected = entry.round == round,
                    onClick = { media.update(entry.id) { it.copy(round = round) } },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.custom_media_spin))
        Spacer(Modifier.width(8.dp))
        Switch(checked = entry.spin, onCheckedChange = { on -> media.update(entry.id) { it.copy(spin = on) } })
    }
}

/** Which songs a visualizer GIF / video is for: an existing item ([entryId]) or a new one ([newUri]). */
@Composable
fun MediaTracksPage(entryId: String?, newUri: String?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val entry = entryId?.let { id -> settings.customMedia.firstOrNull { it.id == id } }
    if (entry == null && newUri == null) {
        LaunchedEffect(Unit) { onBack() } // removed meanwhile
        return
    }
    val name = remember(entry, newUri) { container.customMedia.displayName(entry?.uri ?: newUri.orEmpty()) }
    SongChecklistPage(title = name, initial = entry?.trackUris.orEmpty(), allowNone = entry != null, onBack = onBack) { chosen ->
        if (entry != null) {
            container.customMedia.update(entry.id) { it.copy(trackUris = chosen) }
        } else if (newUri != null) {
            container.customMedia.addEntry(Uri.parse(newUri), chosen)
        }
    }
}
