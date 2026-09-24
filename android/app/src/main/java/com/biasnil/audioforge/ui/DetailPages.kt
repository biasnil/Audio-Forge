package com.biasnil.audioforge.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.searchTracks
import com.biasnil.audioforge.data.sortTracks

/** Top bar with a back arrow, shared by every detail page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.back))
            }
        },
        actions = { actions() },
    )
}

/** An album's or artist's tracks (the desktop's track list dialog). Tapping one plays the group from there. */
@Composable
fun GroupPage(title: String, tracks: List<Track>, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val playback = LocalAppContainer.current.playback
    val state by playback.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { DetailTopBar(title, onBack) }) { innerPadding ->
        if (tracks.isEmpty()) {
            // e.g. the group's last track was retagged or its folder removed
            LaunchedEffect(Unit) { onBack() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding)) {
            itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.uri == state.current?.uri,
                    onClick = { playback.playQueue(tracks, index) },
                    onLongClick = { onOpenPage(Page.EditTags(track.uri)) },
                )
            }
        }
    }
}

@Composable
fun PlaylistPage(playlistId: String, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val libraryTracks by container.library.tracks.collectAsStateWithLifecycle()
    val state by container.playback.state.collectAsStateWithLifecycle()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    val playlist = settings.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() } // deleted
        return
    }
    // Entries keep their playlist position; a track that's no longer in the
    // library (file moved, folder removed) stays listed so it can be removed.
    val entries = remember(playlist.trackUris, libraryTracks) {
        playlist.trackUris.map { uri -> uri to container.library.trackFor(uri) }
    }
    val playable = remember(entries) { entries.mapNotNull { it.second } }

    Scaffold(
        topBar = {
            DetailTopBar(playlist.name, onBack) {
                TextButton(onClick = { onOpenPage(Page.AddTracks(playlistId)) }) {
                    Text(stringResource(R.string.playlist_add_tracks))
                }
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(stringResource(R.string.playlist_delete))
                }
            }
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CenteredText(stringResource(R.string.playlist_is_empty))
            }
        } else {
            LazyColumn(Modifier.padding(innerPadding)) {
                itemsIndexed(entries, key = { index, entry -> "$index:${entry.first}" }) { index, (uri, track) ->
                    val remove: @Composable () -> Unit = {
                        IconButton(onClick = { container.library.removeFromPlaylist(playlistId, index) }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.remove))
                        }
                    }
                    if (track != null) {
                        TrackRow(
                            track = track,
                            isCurrent = track.uri == state.current?.uri,
                            // Queue = the playlist's playable tracks, starting at this one.
                            // (counted by position, so a song that's in the playlist twice starts from this copy)
                            onClick = {
                                container.playback.playQueue(playable, entries.take(index).count { it.second != null })
                            },
                            onLongClick = { onOpenPage(Page.EditTags(track.uri)) },
                            trailing = remove,
                        )
                    } else {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = unavailableTrackLabel(uri),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(stringResource(R.string.playlist_track_unavailable)) },
                            trailingContent = remove,
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.playlist_delete)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    container.library.deletePlaylist(playlistId) // the page then closes itself
                }) { Text(stringResource(R.string.playlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Every library track not already in the playlist, with checkboxes -- the desktop's "Add Tracks..." picker. */
@Composable
fun AddTracksPage(playlistId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val libraryTracks by container.library.tracks.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }

    val playlist = settings.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val candidates = remember(libraryTracks, playlist.trackUris) {
        val alreadyIn = playlist.trackUris.toSet()
        sortTracks(libraryTracks.filter { it.uri !in alreadyIn }, TrackSort.Title)
    }
    val shown = remember(candidates, query) { searchTracks(candidates, query) }

    Scaffold(
        topBar = {
            DetailTopBar(stringResource(R.string.playlist_add_to, playlist.name), onBack) {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        // Added in list order, not tap order.
                        val chosen = selected.toSet()
                        container.library.addToPlaylist(playlistId, candidates.map { it.uri }.filter { it in chosen })
                        onBack()
                    },
                ) { Text(stringResource(R.string.playlist_add_count, selected.size)) }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (candidates.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    CenteredText(stringResource(R.string.playlist_nothing_to_add))
                }
            }
            LazyColumn {
                itemsIndexed(shown, key = { _, track -> track.uri }) { _, track ->
                    val checked = track.uri in selected
                    ListItem(
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(trackSubtitle(track), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable {
                            selected = if (checked) selected - track.uri else selected + track.uri
                        },
                    )
                }
            }
        }
    }
}

/** A readable name for a playlist entry whose file isn't in the library any more. */
@Composable
private fun unavailableTrackLabel(uri: String): String {
    // Document URIs end in e.g. "primary:Music/Song.mp3"; MediaStore ones in a numeric id.
    val name = Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
    return if (name != null && '.' in name) name else stringResource(R.string.playlist_unknown_track)
}
