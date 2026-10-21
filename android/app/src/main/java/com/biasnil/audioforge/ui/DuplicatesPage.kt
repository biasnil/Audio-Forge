package com.biasnil.audioforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.DuplicateGroup
import com.biasnil.audioforge.data.DuplicateResult
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.findDuplicates
import com.biasnil.audioforge.data.pairsAmong
import com.biasnil.audioforge.data.pairsForSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings -> Find Duplicates: copies of the same song, "Same song" (same
 * title and artist) first, then "Similar names". Each copy shows where it is,
 * so you can choose which to keep. Select copies (long-press, or Select) to
 * delete them, or mark a group or some copies "Not duplicates" so they stop
 * showing up. Nothing is ever deleted automatically.
 */
@Composable
fun DuplicatesPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val dismissed by container.duplicateDismissals.pairs.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val trackActions = LocalTrackActions.current
    val result by produceState<DuplicateResult?>(null, library, dismissed) {
        value = withContext(Dispatchers.Default) { findDuplicates(library, dismissed) }
    }
    val title = stringResource(R.string.duplicates)

    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showDismissed by rememberSaveable { mutableStateOf(false) }
    val endSelecting = {
        selecting = false
        selection = emptySet()
    }
    BackHandler(enabled = selecting, onBack = endSelecting)

    /** Dismisses [pairs], with Undo. */
    fun dismiss(pairs: Set<String>) {
        if (pairs.isEmpty()) return
        val before = container.duplicateDismissals.pairs.value
        container.duplicateDismissals.dismiss(pairs)
        trackActions.showUndoable(context.getString(R.string.duplicates_dismissed_message)) {
            container.duplicateDismissals.set(before)
        }
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                title = if (selecting) stringResource(R.string.selected_count, selection.size) else title,
                onBack = if (selecting) endSelecting else onBack,
            ) {
                if (result?.groups?.isNotEmpty() == true) {
                    TextButton(onClick = { if (selecting) endSelecting() else selecting = true }) {
                        Text(stringResource(if (selecting) R.string.done else R.string.duplicates_select))
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) {
                val groups = result?.groups.orEmpty()
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                // "These copies aren't copies of the rest of their group."
                                val pairs = HashSet<String>()
                                for (group in groups) {
                                    val uris = group.tracks.map { it.uri }
                                    val chosen = uris.filter { it in selection }
                                    if (chosen.isNotEmpty()) pairs += pairsForSelection(chosen, uris)
                                }
                                endSelecting()
                                dismiss(pairs)
                            },
                        ) { Text(stringResource(R.string.duplicates_not_duplicates)) }
                        TextButton(
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                val picked = groups.flatMap { it.tracks }.filter { it.uri in selection }.distinctBy { it.uri }
                                endSelecting()
                                trackActions.delete(picked) // asks first, then the system confirms too
                            },
                        ) { Text(stringResource(R.string.delete_ellipsis), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
    ) { innerPadding ->
        val found = result
        if (found == null) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val same = found.groups.filter { !it.similar }
        val similar = found.groups.filter { it.similar }
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            if (found.groups.isEmpty()) {
                item(key = "none") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CenteredText(stringResource(R.string.duplicates_none))
                        if (found.dismissed.isNotEmpty()) {
                            Text(
                                text = pluralStringResource(R.plurals.duplicates_dismissed_count, found.dismissed.size, found.dismissed.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            val rowsFor: LazyListScope.(String, List<DuplicateGroup>) -> Unit = { section, groups ->
                groups.forEachIndexed { groupIndex, group ->
                    val uris = group.tracks.map { it.uri }
                    item(key = "$section:header:$groupIndex:${uris.first()}") {
                        GroupedHeader(stringResource(R.string.duplicates_group, group.tracks[0].title, group.tracks.size)) {
                            if (group.similar) SimilarLabel()
                            TextButton(onClick = { dismiss(pairsAmong(uris)) }) {
                                Text(stringResource(R.string.duplicates_not_duplicates))
                            }
                        }
                    }
                    itemsIndexed(group.tracks, key = { _, track -> "$section:$groupIndex:" + track.uri }) { index, track ->
                        val toggle = { selection = if (track.uri in selection) selection - track.uri else selection + track.uri }
                        TrackRow(
                            track = track,
                            isCurrent = !selecting && track.uri == playback.current?.uri,
                            selected = if (selecting) track.uri in selection else null,
                            onClick = if (selecting) toggle else ({ container.playback.playQueue(group.tracks, index, from = title) }),
                            onLongClick = {
                                if (!selecting) selecting = true
                                toggle()
                            },
                            trailing = if (track.uri == group.best) ({ BestQualityLabel() }) else null,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            shape = groupedShape(index, group.tracks.size),
                            showDivider = index < group.tracks.lastIndex,
                            extraLine = location(container.library.trackPath(track), track),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            if (same.isNotEmpty()) {
                item(key = "same") { SectionHeader(stringResource(R.string.duplicates_same_song, same.size)) }
                rowsFor("same", same)
            }
            if (similar.isNotEmpty()) {
                item(key = "similar") { SectionHeader(stringResource(R.string.duplicates_similar_names, similar.size)) }
                rowsFor("similar", similar)
            }

            // Groups marked "Not duplicates", each with Restore.
            if (found.dismissed.isNotEmpty()) {
                item(key = "dismissed") {
                    TextButton(
                        onClick = { showDismissed = !showDismissed },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Text(
                            if (showDismissed) stringResource(R.string.duplicates_hide_dismissed)
                            else stringResource(R.string.duplicates_show_dismissed, found.dismissed.size)
                        )
                    }
                }
                if (showDismissed) {
                    found.dismissed.forEachIndexed { groupIndex, group ->
                        val uris = group.tracks.map { it.uri }
                        item(key = "dismissed:header:$groupIndex:${uris.first()}") {
                            GroupedHeader(stringResource(R.string.duplicates_group, group.tracks[0].title, group.tracks.size)) {
                                TextButton(onClick = { container.duplicateDismissals.restore(pairsAmong(uris)) }) {
                                    Text(stringResource(R.string.duplicates_restore))
                                }
                            }
                        }
                        itemsIndexed(group.tracks, key = { _, track -> "dismissed:$groupIndex:" + track.uri }) { index, track ->
                            TrackRow(
                                track = track,
                                isCurrent = false,
                                onClick = { container.playback.playQueue(group.tracks, index, from = title) },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                shape = groupedShape(index, group.tracks.size),
                                showDivider = index < group.tracks.lastIndex,
                                extraLine = location(container.library.trackPath(track), track),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** The small "Similar" tag on a Similar names group. */
@Composable
private fun SimilarLabel() {
    Text(
        text = stringResource(R.string.duplicates_similar_label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** On the copy worth keeping: highest bitrate, then lossless. */
@Composable
private fun BestQualityLabel() {
    Text(
        text = stringResource(R.string.duplicates_best),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** "Music/Rock/a.mp3 · 320 kbps · MP3" -- enough to decide which copy to keep. */
private fun location(path: String, track: Track): String {
    val parts = mutableListOf(path)
    if (track.bitrateKbps > 0) parts += "${track.bitrateKbps} kbps"
    track.fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { parts += it.uppercase(Locale.ROOT) }
    return parts.joinToString(" · ")
}
