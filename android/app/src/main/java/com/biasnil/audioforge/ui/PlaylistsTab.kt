package com.biasnil.audioforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Playlist
import com.biasnil.audioforge.data.SmartPlaylist
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Playlists tab, like the iOS one: the smart lists, then your playlists with their covers. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable, on older Compose versions
@Composable
fun PlaylistsTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val libraryTracks by container.library.tracks.collectAsStateWithLifecycle()
    val changeCover = rememberPlaylistCoverPicker()

    var menuFor by remember { mutableStateOf<Playlist?>(null) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var smartMenuFor by remember { mutableStateOf<SmartPlaylist?>(null) }

    val smartLists = SmartPlaylist.BUILT_IN + settings.smartPlaylists
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "smartHeader") { GroupedHeader(stringResource(R.string.smart_lists)) }
        itemsIndexed(smartLists, key = { _, smart -> "smart:" + smart.id }) { index, smart ->
            GroupedRow(
                index = index,
                count = smartLists.size,
                onClick = { onOpenPage(Page.SmartPlaylistDetail(smart.id)) },
                onLongClick = if (smart.builtIn) null else ({ smartMenuFor = smart }),
            ) {
                Icon(smartIcon(smart), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(smartName(smart), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item(key = "playlistsHeader") { GroupedHeader(stringResource(R.string.tab_playlists)) }
        if (settings.playlists.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.playlists_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                )
            }
        }
        itemsIndexed(settings.playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            val tracks = playlist.trackUris.mapNotNull(container.library::trackFor)
            GroupedRow(
                index = index,
                count = settings.playlists.size,
                onClick = { onOpenPage(Page.PlaylistDetail(playlist.id)) },
                onLongClick = { menuFor = playlist },
                vertical = 8.dp,
            ) {
                PlaylistCover(playlist, tracks, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = pluralStringResource(R.plurals.song_count, tracks.size, tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "bottom") { Spacer(Modifier.padding(bottom = 16.dp)) }
    }

    // libraryTracks is read so the counts follow rescans.
    libraryTracks.size

    menuFor?.let { playlist ->
        val tracks = playlist.trackUris.mapNotNull(container.library::trackFor)
        MenuDialog(title = playlist.name, onDismiss = { menuFor = null }) { close ->
            MenuDialogButton(stringResource(R.string.queue_play_next)) { close(); trackActions.playNext(tracks) }
            MenuDialogButton(stringResource(R.string.queue_add)) { close(); trackActions.addToQueue(tracks) }
            MenuDialogButton(stringResource(R.string.rename)) { close(); renaming = playlist }
            MenuDialogButton(stringResource(R.string.cover_change)) { close(); changeCover(playlist.id) }
            if (playlist.coverFile != null) {
                MenuDialogButton(stringResource(R.string.playlist_use_album_covers)) {
                    close()
                    container.library.removePlaylistCover(playlist.id)
                }
            }
            MenuDialogButton(stringResource(R.string.playlist_delete), destructive = true) { close(); deleting = playlist }
        }
    }
    renaming?.let { playlist ->
        NameDialog(
            title = stringResource(R.string.playlist_rename),
            initial = playlist.name,
            confirm = stringResource(R.string.save),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                container.library.renamePlaylist(playlist.id, name)
            },
        )
    }
    deleting?.let { playlist ->
        ConfirmDeletePlaylist(playlist.name, onDismiss = { deleting = null }) {
            deleting = null
            container.library.deletePlaylist(playlist.id)
        }
    }
    smartMenuFor?.let { smart ->
        MenuDialog(title = smartName(smart), onDismiss = { smartMenuFor = null }) { close ->
            MenuDialogButton(stringResource(R.string.smart_edit_rules)) { close(); onOpenPage(Page.SmartPlaylistEdit(smart.id)) }
            MenuDialogButton(stringResource(R.string.playlist_delete), destructive = true) {
                close()
                container.library.deleteSmartPlaylist(smart.id)
            }
        }
    }
}

/** The Playlists title bar's "+": New Playlist, New Smart Playlist, Import .m3u. */
@Composable
fun RowScope.PlaylistsHeaderActions(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val importM3u = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val messages = uris.mapNotNull { uri ->
                try {
                    container.library.importM3u(uri)?.let { (id, matched, total) ->
                        val name = container.store.settings.value.playlists.firstOrNull { it.id == id }?.name.orEmpty()
                        context.getString(R.string.m3u_imported, name, matched, total)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            trackActions.showMessage(messages.joinToString("\n").ifEmpty { context.getString(R.string.m3u_nothing_found) })
        }
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(AppIcons.Add, contentDescription = stringResource(R.string.playlist_new))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_new)) }, onClick = {
                expanded = false
                creating = true
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.smart_new)) }, onClick = {
                expanded = false
                onOpenPage(Page.SmartPlaylistEdit(null))
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.m3u_import)) }, onClick = {
                expanded = false
                // .m3u files are typed inconsistently (audio/x-mpegurl, audio/mpegurl, text/plain, octet-stream...).
                importM3u.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream"))
            })
        }
    }

    if (creating) {
        NewPlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                val id = container.library.createPlaylist(name)
                // Open the new playlist with the track picker on top -- the
                // desktop's create dialog was name + track checkboxes in one.
                onOpenPage(Page.PlaylistDetail(id))
                onOpenPage(Page.AddTracks(id))
            },
        )
    }
}

/**
 * A playlist's picture: your own image, or a grid of its first four album
 * covers (one cover if it has fewer albums), like the iOS app.
 */
@Composable
fun PlaylistCover(playlist: Playlist, tracks: List<Track>, size: Dp, modifier: Modifier = Modifier) {
    val radius = if (size > 60.dp) 10.dp else 6.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val file = playlist.coverFile
        if (file != null) {
            val container = LocalAppContainer.current
            val px = with(LocalDensity.current) { size.roundToPx() }
            val bitmap by produceState<Bitmap?>(null, file, px) {
                value = withContext(Dispatchers.IO) { decodeScaled(container.library.coverFile(file).path, px) }
            }
            bitmap?.let {
                val image = remember(it) { it.asImageBitmap() }
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            return@Box
        }
        // One song per album, in playlist order.
        val covers = remember(tracks) { tracks.distinctBy { it.album.trim().lowercase() to it.folderCoverUri }.take(4) }
        when {
            covers.size >= 4 -> Column {
                for (row in 0..1) {
                    Row {
                        for (column in 0..1) {
                            CoverImage(covers[row * 2 + column], decodeSize = size / 2, cornerRadius = 0.dp, modifier = Modifier.size(size / 2))
                        }
                    }
                }
            }
            covers.isNotEmpty() -> CoverImage(covers[0], decodeSize = size, cornerRadius = 0.dp, modifier = Modifier.fillMaxSize())
            else -> Icon(
                AppIcons.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

private fun decodeScaled(path: String, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Pick an image with the photo picker and make it a playlist's cover. Call with the playlist id. */
@Composable
fun rememberPlaylistCoverPicker(): (String) -> Unit {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageUri ->
        val id = target
        target = null
        if (imageUri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val image = container.tagEditor.prepareCoverImage(imageUri)
                container.library.setPlaylistCover(id, image.bytes, image.mimeType)
            } catch (e: Exception) {
                trackActions.showMessage(context.getString(R.string.cover_error_image))
            }
        }
    }
    return { id ->
        target = id
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** A built-in smart list's name in the app's language; your own keep theirs. */
@Composable
fun smartName(smart: SmartPlaylist): String = when (smart.id) {
    SmartPlaylist.LOVED_ID -> stringResource(R.string.smart_loved)
    SmartPlaylist.MOST_PLAYED_ID -> stringResource(R.string.smart_most_played)
    SmartPlaylist.RECENTLY_PLAYED_ID -> stringResource(R.string.smart_recently_played)
    SmartPlaylist.RECENTLY_ADDED_ID -> stringResource(R.string.smart_recently_added)
    SmartPlaylist.NEVER_PLAYED_ID -> stringResource(R.string.smart_never_played)
    else -> smart.name.ifEmpty { stringResource(R.string.smart_playlist) }
}

fun smartIcon(smart: SmartPlaylist): ImageVector = when (smart.id) {
    SmartPlaylist.LOVED_ID -> AppIcons.Heart
    SmartPlaylist.MOST_PLAYED_ID -> AppIcons.Flame
    SmartPlaylist.RECENTLY_PLAYED_ID -> AppIcons.History
    SmartPlaylist.RECENTLY_ADDED_ID -> AppIcons.AddCircle
    SmartPlaylist.NEVER_PLAYED_ID -> AppIcons.Sparkle
    else -> AppIcons.Tune
}

/** One row of a grouped card (rounded at the top/bottom of its section). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    vertical: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(groupedShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = vertical),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (index < count - 1) {
            HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** A long-press menu as a dialog of buttons; each button gets a `close` to call. */
@Composable
fun MenuDialog(title: String, onDismiss: () -> Unit, buttons: @Composable (close: () -> Unit) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { buttons(onDismiss) } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun MenuDialogButton(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

/** Also Up next's "Save as playlist" and Add to Playlist's "New Playlist...". */
@Composable
internal fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    NameDialog(
        title = stringResource(R.string.playlist_new),
        initial = "",
        confirm = stringResource(R.string.playlist_create),
        onDismiss = onDismiss,
        onConfirm = onCreate,
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun ConfirmDeletePlaylist(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete)) },
        text = { Text(stringResource(R.string.playlist_delete_confirm, name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
