package com.biasnil.audioforge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.FoundInfo
import com.biasnil.audioforge.data.RemoteImages
import com.biasnil.audioforge.data.fillEmptyFields
import com.biasnil.audioforge.data.lookupNames
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Tag editor -> Find missing info: looks the song up (title and artist go to
 * Apple, then Deezer), fills only the empty Album, Album artist, Year,
 * Genre and Track fields -- the user still checks them and taps Save -- and
 * offers the cover it found, which [onUseCover] embeds. A video-style name
 * ("NSYNC - Bye Bye Bye (Lyrics)" by a lyrics channel) is cleaned up first
 * (see lookupNames), and then Title and Artist are fixed too.
 */
@Composable
fun FindMissingInfoSection(
    fields: List<Pair<String, String>>,
    enabled: Boolean,
    onFill: (List<Pair<String, String>>) -> Unit,
    onUseCover: (ByteArray) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<FoundInfo?>(null) }
    var downloadingCover by remember { mutableStateOf(false) }

    val title = fields.firstOrNull { it.first == "TITLE" }?.second.orEmpty()
    val artist = fields.firstOrNull { it.first == "ARTIST" }?.second.orEmpty()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    searching = true
                    message = null
                    found = null
                    scope.launch {
                        try {
                            // "NSYNC - Bye Bye Bye (Lyrics)" by "7clouds" is looked up as Bye Bye Bye by NSYNC.
                            val names = lookupNames(title, artist, container.store.settings.value.titleRules)
                            var info: FoundInfo? = null
                            var usedSearch: Pair<String, String>? = null
                            for (search in names.searches) {
                                info = container.metadata.findSong(search.first, search.second)
                                if (info != null) {
                                    usedSearch = search
                                    break
                                }
                            }
                            // The title and artist are fixed when they needed it: to the song found, or
                            // to the cleaned-up names when nothing was found.
                            val typed = title.trim() to artist.trim()
                            val fixedNames = when {
                                info != null && usedSearch != typed && info.title.isNotBlank() -> info.title to info.artist.ifBlank { names.artist }
                                info == null && names.cleaned -> names.title to names.artist
                                else -> null
                            }
                            val renamed = fixedNames?.let { (newTitle, newArtist) -> withTitleAndArtist(fields, newTitle, newArtist) } ?: fields
                            if (info == null) {
                                if (renamed != fields) onFill(renamed)
                                message = context.getString(if (renamed != fields) R.string.autofill_fixed_not_found else R.string.autofill_not_found)
                            } else {
                                found = info
                                val (updated, filled) = fillEmptyFields(renamed, info)
                                onFill(updated)
                                val fixed = renamed != fields
                                message = when {
                                    fixed && filled.isEmpty() -> context.getString(R.string.autofill_fixed, info.source)
                                    fixed -> context.getString(R.string.autofill_fixed_filled, filled.size, info.source)
                                    filled.isEmpty() -> context.getString(R.string.autofill_nothing_new, info.source)
                                    else -> context.getString(R.string.autofill_filled, filled.size, info.source)
                                }
                            }
                        } catch (e: IOException) {
                            message = context.getString(R.string.autofill_offline)
                        } finally {
                            searching = false
                        }
                    }
                },
                enabled = enabled && !searching && title.isNotBlank(),
            ) { Text(stringResource(if (searching) R.string.autofill_searching else R.string.autofill_find)) }
            InfoButton(stringResource(R.string.autofill_note))
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        found?.coverUrl?.let { url ->
            val preview by produceState<Bitmap?>(null, url) { value = RemoteImages.load(url, 200) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                preview?.let { bitmap ->
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(image, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        stringResource(R.string.autofill_cover_from, found?.source.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            downloadingCover = true
                            scope.launch {
                                try {
                                    onUseCover(container.metadata.downloadImage(url))
                                } catch (e: IOException) {
                                    message = context.getString(R.string.autofill_offline)
                                } finally {
                                    downloadingCover = false
                                }
                            }
                        },
                        enabled = enabled && !downloadingCover,
                    ) { Text(stringResource(R.string.autofill_use_cover)) }
                }
            }
        }
    }
}

/** [fields] with TITLE and ARTIST set (added if the list had none). */
private fun withTitleAndArtist(fields: List<Pair<String, String>>, title: String, artist: String): List<Pair<String, String>> {
    val wanted = mapOf("TITLE" to title, "ARTIST" to artist).filterValues { it.isNotBlank() }
    val updated = fields.map { (key, value) -> key to (wanted[key] ?: value) }
    return updated + wanted.filterKeys { key -> fields.none { it.first == key } }.toList()
}

/**
 * Settings -> Library -> Find missing covers: albums with no cover at all
 * get one from Apple or Deezer (album name and artist are sent), saved in
 * the app -- the music files aren't changed. Progress, and Stop.
 */
@Composable
fun FindMissingCoversRow() {
    val container = LocalAppContainer.current
    val progress by container.metadata.coverProgress.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val running = progress
        if (running == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { container.metadata.findMissingCovers(tracks) }, enabled = tracks.isNotEmpty()) {
                    Text(stringResource(R.string.covers_find))
                }
                InfoButton(stringResource(R.string.covers_find_explanation), title = stringResource(R.string.covers_find))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.covers_progress, running.done, running.total, running.found),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = container.metadata::stopFindingCovers) { Text(stringResource(R.string.analysis_stop)) }
            }
            LinearProgressIndicator(
                progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
