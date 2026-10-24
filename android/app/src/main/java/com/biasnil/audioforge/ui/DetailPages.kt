package com.biasnil.audioforge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.searchTracks
import com.biasnil.audioforge.data.sortTracks

/** Play next / Add to queue for a whole album, artist or playlist. */
@Composable
private fun QueueMenuItems(tracks: List<Track>, close: () -> Unit) {
    val trackActions = LocalTrackActions.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.queue_play_next)) },
        enabled = tracks.isNotEmpty(),
        onClick = {
            close()
            trackActions.playNext(tracks)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.queue_add)) },
        enabled = tracks.isNotEmpty(),
        onClick = {
            close()
            trackActions.addToQueue(tracks)
        },
    )
}

/**
 * An album's, artist's or genre's songs (the iOS SongCollectionView): a
 * cover header with Play and Shuffle, then the songs. Tapping one plays the
 * group from there.
 */
@Composable
fun GroupPage(title: String, tracks: List<Track>, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val playback = LocalAppContainer.current.playback
    val trackActions = LocalTrackActions.current
    val state by playback.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            DetailTopBar(title, onBack) {
                OverflowMenu { close -> QueueMenuItems(tracks, close) }
            }
        },
        bottomBar = {
            MiniPlayerBar(
                state = state,
                onOpenNowPlaying = { onOpenPage(Page.NowPlaying) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        if (tracks.isEmpty()) {
            // e.g. the group's last track was retagged or its folder removed
            LaunchedEffect(Unit) { onBack() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(innerPadding)) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverImage(
                        track = tracks.firstOrNull { it.folderCoverUri != null } ?: tracks.first(),
                        decodeSize = 220.dp,
                        cornerRadius = 10.dp,
                        modifier = Modifier
                            .size(220.dp)
                            .shadow(12.dp, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, tracks.size, tracks.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { playback.playQueue(tracks, 0, from = title) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play))
                        }
                        FilledTonalButton(
                            onClick = { playback.playShuffled(tracks, from = title) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(AppIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.uri == state.current?.uri,
                    onClick = { playback.playQueue(tracks, index, from = title) },
                    onLongClick = { trackActions.showMenu(track) },
                )
            }
        }
    }
}

/**
 * One playlist, like the iOS PlaylistDetailView: its cover (tap to change),
 * Play / Shuffle, then the songs -- drag the handle to reorder, ✕ to
 * remove. A song that's no longer in the library stays listed (so it can be
 * removed). ⋯ has queueing, the cover, .m3u export, rename and delete.
 */
@Composable
fun PlaylistPage(playlistId: String, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val libraryTracks by container.library.tracks.collectAsStateWithLifecycle()
    val state by container.playback.state.collectAsStateWithLifecycle()
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changeCover = rememberPlaylistCoverPicker()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }

    val playlist = settings.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() } // deleted
        return
    }
    // Each entry keyed by URI + which copy of it this is, so reordering keeps identities.
    val entries = remember(playlist.trackUris, libraryTracks) {
        val seen = HashMap<String, Int>()
        playlist.trackUris.map { uri ->
            val copy = seen.merge(uri, 1, Int::plus) ?: 1
            PlaylistRow("$uri#$copy", uri, container.library.trackFor(uri))
        }
    }
    val playable = remember(entries) { entries.mapNotNull { it.track } }
    val listState = rememberLazyListState()
    val reorder = remember(listState) { ReorderState<PlaylistRow>(listState) { it.key } }
    LaunchedEffect(playlist.trackUris) { reorder.clear() }
    val latestEntries = rememberUpdatedState(entries)
    val shown = reorder.order ?: entries

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                container.library.exportM3u(playable, uri)
                trackActions.showMessage(context.getString(R.string.m3u_exported, playlist.name))
            } catch (e: Exception) {
                trackActions.showMessage(context.getString(R.string.m3u_export_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            DetailTopBar(playlist.name, onBack) {
                IconButton(onClick = { onOpenPage(Page.AddTracks(playlistId)) }) {
                    Icon(AppIcons.Add, contentDescription = stringResource(R.string.playlist_add_tracks))
                }
                PlaylistMenu(
                    playable = playable,
                    hasOwnCover = playlist.coverFile != null,
                    onChangeCover = { changeCover(playlistId) },
                    onUseAlbumCovers = { container.library.removePlaylistCover(playlistId) },
                    onExport = { export.launch(playlist.name.replace('/', '-') + ".m3u8") },
                    onRename = { renaming = true },
                    onDelete = { confirmingDelete = true },
                )
            }
        },
        bottomBar = {
            MiniPlayerBar(
                state = state,
                onOpenNowPlaying = { onOpenPage(Page.NowPlaying) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        LazyColumn(state = listState, modifier = Modifier.padding(innerPadding)) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlaylistCover(
                        playlist = playlist,
                        tracks = playable,
                        size = 200.dp,
                        modifier = Modifier
                            .shadow(12.dp, RoundedCornerShape(10.dp))
                            .clickable { changeCover(playlistId) },
                    )
                    if (playable.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        PlayShuffleButtons(
                            onPlay = { container.playback.playQueue(playable, 0, from = playlist.name) },
                            onShuffle = { container.playback.playShuffled(playable, from = playlist.name) },
                        )
                    }
                }
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    CenteredText(
                        stringResource(R.string.playlist_is_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, row -> row.key }) { index, row ->
                val dragged = row.key == reorder.draggedKey
                val rowModifier = if (dragged) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer { translationY = reorder.offset }
                        .shadow(8.dp)
                        .background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier.animateItem()
                }
                val trailing: @Composable () -> Unit = {
                    RemoveAndDragControls(
                        onRemove = {
                            val position = entries.indexOfFirst { it.key == row.key }
                            if (position >= 0) container.library.removeFromPlaylist(playlistId, position)
                        },
                        dragHandle = reorder.handleModifier(row.key, { latestEntries.value }) { moved ->
                            container.library.setPlaylistUris(playlistId, moved.map { it.uri })
                        },
                    )
                }
                val track = row.track
                if (track != null) {
                    TrackRow(
                        track = track,
                        isCurrent = track.uri == state.current?.uri,
                        // Queue = the playlist's playable tracks, starting at this one.
                        // (counted by position, so a song that's in the playlist twice starts from this copy)
                        onClick = {
                            container.playback.playQueue(
                                playable,
                                shown.take(index).count { it.track != null },
                                from = playlist.name,
                            )
                        },
                        onLongClick = { trackActions.showMenu(track) },
                        trailing = trailing,
                        modifier = rowModifier,
                    )
                } else {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = unavailableTrackLabel(row.uri),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.playlist_track_unavailable)) },
                        trailingContent = trailing,
                        modifier = rowModifier,
                    )
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.playlist_rename),
            initial = playlist.name,
            confirm = stringResource(R.string.save),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                container.library.renamePlaylist(playlistId, name)
            },
        )
    }
    if (confirmingDelete) {
        ConfirmDeletePlaylist(playlist.name, onDismiss = { confirmingDelete = false }) {
            confirmingDelete = false
            container.library.deletePlaylist(playlistId) // the page then closes itself
        }
    }
}

/** A playlist page's ⋮ menu: queueing, the cover, .m3u export, rename and delete. */
@Composable
private fun PlaylistMenu(
    playable: List<Track>,
    hasOwnCover: Boolean,
    onChangeCover: () -> Unit,
    onUseAlbumCovers: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    OverflowMenu { close ->
        QueueMenuItems(playable, close)
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.cover_change)) }, onClick = {
            close()
            onChangeCover()
        })
        if (hasOwnCover) {
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_use_album_covers)) }, onClick = {
                close()
                onUseAlbumCovers()
            })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.m3u_export)) }, enabled = playable.isNotEmpty(), onClick = {
            close()
            onExport()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
            close()
            onRename()
        })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) },
            onClick = {
                close()
                onDelete()
            },
        )
    }
}

/** A playlist row's ✕ (remove) button and drag handle; [dragHandle] comes from [ReorderState.handleModifier]. */
@Composable
private fun RemoveAndDragControls(onRemove: () -> Unit, dragHandle: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(AppIcons.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(18.dp))
        }
        Icon(
            imageVector = AppIcons.DragHandle,
            contentDescription = stringResource(R.string.queue_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .padding(8.dp)
                .then(dragHandle),
        )
    }
}

/** One playlist entry: [key] is unique even when a song is in the playlist twice. */
private data class PlaylistRow(val key: String, val uri: String, val track: Track?)

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
    val groups by container.library.groups.collectAsStateWithLifecycle()
    val alreadyIn = remember(playlist.trackUris) { playlist.trackUris.toSet() }
    val shown = rememberInBackground(groups, alreadyIn, query, debounceMs = if (query.isBlank()) 0 else SEARCH_DELAY_MS) {
        searchTracks(groups.byTitle.filter { it.uri !in alreadyIn }, query)
    }.orEmpty()

    Scaffold(
        topBar = {
            DetailTopBar(stringResource(R.string.playlist_add_to, playlist.name), onBack) {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        // Added in list order, not tap order.
                        val chosen = selected.toSet()
                        container.library.addToPlaylist(playlistId, groups.byTitle.map { it.uri }.filter { it in chosen })
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
            if (groups.ready && shown.isEmpty() && query.isBlank()) {
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
