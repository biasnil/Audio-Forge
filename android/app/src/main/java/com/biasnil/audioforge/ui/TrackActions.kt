package com.biasnil.audioforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.folderPath
import kotlinx.coroutines.launch

/** What long-pressing a song offers, available anywhere below [AudioForgeApp]. */
class TrackActions(
    /** The long-press menu: Play next / Add to queue / Edit tags / Change cover. */
    val showMenu: (Track) -> Unit,
    /** Straight to the image picker (the tag editor's "Change cover" button). */
    val changeCover: (Track) -> Unit,
    /** Long-press menu for an album, artist or playlist: Play next / Add to queue for all of its songs. */
    val showGroupMenu: (title: String, tracks: List<Track>) -> Unit,
    /** Queues the songs (or plays them, if nothing is playing) and says so in a snackbar. */
    val playNext: (List<Track>) -> Unit,
    val addToQueue: (List<Track>) -> Unit,
    /** The app's snackbar. */
    val showMessage: (String) -> Unit,
    /** The snackbar with an Undo button; the lambda runs if it's tapped. */
    val showUndoable: (String, () -> Unit) -> Unit,
    /** Choose a playlist (or make a new one) and add the songs to it. */
    val addToPlaylist: (List<Track>) -> Unit,
    /** Asks, then deletes the songs' files (phone-library files need the system's OK too). */
    val delete: (List<Track>) -> Unit,
)

val LocalTrackActions = staticCompositionLocalOf<TrackActions> { error("TrackActions not provided") }

/**
 * The long-press menu, plus the "Change cover" flow: pick an image with the
 * system photo picker (no storage permission needed), then write it into
 * the song -- the desktop's "Change Cover..." button.
 */
@Composable
fun rememberTrackActions(
    onOpenPage: (Page) -> Unit,
    showMessage: (String) -> Unit,
    /** A message with an Undo button; the lambda runs if it's tapped. */
    showUndoable: (String, () -> Unit) -> Unit,
): TrackActions {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeFile = rememberTrackFileWriter()

    var menuTrackUri by rememberSaveable { mutableStateOf<String?>(null) }
    var coverTrackUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun trackFor(uri: String): Track? =
        container.library.trackFor(uri) ?: container.playback.state.value.current?.takeIf { it.uri == uri }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageUri ->
        val target = coverTrackUri?.let(::trackFor)
        coverTrackUri = null
        if (imageUri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = try {
                container.tagEditor.prepareCoverImage(imageUri)
            } catch (e: Exception) {
                showMessage(context.getString(R.string.cover_error_image))
                return@launch
            }
            writeFile(target, { container.tagEditor.writeCover(target, image) }) { error ->
                when (error) {
                    null -> showMessage(context.getString(R.string.cover_changed))
                    WriteCancelled -> Unit
                    else -> showMessage(errorMessage(context, error))
                }
            }
        }
    }
    val changeCover: (Track) -> Unit = { track ->
        coverTrackUri = track.uri
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val playNext: (List<Track>) -> Unit = { tracks ->
        if (container.playback.playNext(tracks)) {
            showMessage(
                if (tracks.size == 1) context.getString(R.string.queue_playing_next_one, tracks[0].title)
                else context.resources.getQuantityString(R.plurals.queue_playing_next_many, tracks.size, tracks.size)
            )
        }
    }
    val addToQueue: (List<Track>) -> Unit = { tracks ->
        if (container.playback.addToQueue(tracks)) {
            showMessage(
                if (tracks.size == 1) context.getString(R.string.queue_added_one, tracks[0].title)
                else context.resources.getQuantityString(R.plurals.queue_added_many, tracks.size, tracks.size)
            )
        }
    }

    // --- Add to playlist -----------------------------------------------------------
    // Not saveable (song lists); after a rotation these dialogs are just gone.
    var playlistPickFor by remember { mutableStateOf<List<Track>?>(null) }
    var newPlaylistFor by remember { mutableStateOf<List<Track>?>(null) }
    playlistPickFor?.let { tracks ->
        val playlists = container.store.settings.value.playlists
        AlertDialog(
            onDismissRequest = { playlistPickFor = null },
            title = { Text(stringResource(R.string.playlist_add_to_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                playlistPickFor = null
                                container.library.addToPlaylistUnique(playlist.id, tracks.map { it.uri })
                                showMessage(
                                    context.resources.getQuantityString(
                                        R.plurals.playlist_added_songs, tracks.size, tracks.size, playlist.name,
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    TextButton(
                        onClick = {
                            playlistPickFor = null
                            newPlaylistFor = tracks
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.playlist_new_ellipsis)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { playlistPickFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    newPlaylistFor?.let { tracks ->
        NewPlaylistDialog(
            onDismiss = { newPlaylistFor = null },
            onCreate = { name ->
                newPlaylistFor = null
                val id = container.library.createPlaylist(name)
                container.library.addToPlaylist(id, tracks.map { it.uri })
                showMessage(context.resources.getQuantityString(R.plurals.playlist_added_songs, tracks.size, tracks.size, name.trim()))
            },
        )
    }
    val addToPlaylist: (List<Track>) -> Unit = { tracks -> if (tracks.isNotEmpty()) playlistPickFor = tracks }

    // --- Delete ---------------------------------------------------------------------
    var pendingDelete by remember { mutableStateOf<List<Track>?>(null) }
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        container.library.refresh() // whatever the answer, rescan
    }
    pendingDelete?.let { tracks ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    if (tracks.size == 1) stringResource(R.string.delete_one_title, tracks[0].title)
                    else stringResource(R.string.delete_many_title, tracks.size)
                )
            },
            text = { Text(stringResource(R.string.delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    // Don't keep playing a file that's about to go.
                    if (tracks.any { it.uri == container.playback.state.value.current?.uri }) container.playback.next()
                    scope.launch {
                        val request = container.library.deleteTracks(tracks)
                        if (request != null) systemDelete.launch(IntentSenderRequest.Builder(request).build())
                    }
                }) { Text(stringResource(R.string.playlist_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val delete: (List<Track>) -> Unit = { tracks -> if (tracks.isNotEmpty()) pendingDelete = tracks }

    var groupMenu by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }
    groupMenu?.let { (title, tracks) ->
        AlertDialog(
            onDismissRequest = { groupMenu = null },
            title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    QueueMenuButtons(
                        onPlayNext = {
                            groupMenu = null
                            playNext(tracks)
                        },
                        onAddToQueue = {
                            groupMenu = null
                            addToQueue(tracks)
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { groupMenu = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    menuTrackUri?.let { uri ->
        val track = trackFor(uri)
        if (track == null) {
            menuTrackUri = null
        } else {
            AlertDialog(
                onDismissRequest = { menuTrackUri = null },
                title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        QueueMenuButtons(
                            onPlayNext = {
                                menuTrackUri = null
                                playNext(listOf(track))
                            },
                            onAddToQueue = {
                                menuTrackUri = null
                                addToQueue(listOf(track))
                            },
                        )
                        val loved = container.songData.isFavorite(track.uri)
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                container.songData.toggleFavorite(track.uri)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(if (loved) R.string.unlove else R.string.love)) }
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                addToPlaylist(listOf(track))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.playlist_add_to_title)) }
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                onOpenPage(Page.EditTags(track.uri))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.tags_edit)) }
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                changeCover(track)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cover_change)) }
                        // Phone-library songs only: hide their whole folder (WhatsApp audio, ringtones...).
                        if (track.folderKey.isNotEmpty()) {
                            val folderName = folderPath(track.folderKey).substringAfterLast('/')
                            TextButton(
                                onClick = {
                                    menuTrackUri = null
                                    container.library.setFolderHidden(track.folderKey, hidden = true)
                                    showUndoable(context.getString(R.string.folder_hidden, folderName)) {
                                        container.library.setFolderHidden(track.folderKey, hidden = false)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.folder_hide, folderName)) }
                        }
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                delete(listOf(track))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.delete_song), color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { menuTrackUri = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    return remember {
        TrackActions(
            showMenu = { menuTrackUri = it.uri },
            changeCover = changeCover,
            showGroupMenu = { title, tracks -> if (tracks.isNotEmpty()) groupMenu = title to tracks },
            playNext = playNext,
            addToQueue = addToQueue,
            showMessage = showMessage,
            showUndoable = showUndoable,
            addToPlaylist = addToPlaylist,
            delete = delete,
        )
    }
}

@Composable
private fun QueueMenuButtons(onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    TextButton(onClick = onPlayNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.queue_play_next))
    }
    TextButton(onClick = onAddToQueue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.queue_add))
    }
}
