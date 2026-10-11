package com.biasnil.audioforge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.TrackGroup
import com.biasnil.audioforge.data.albumArtist
import com.biasnil.audioforge.data.albumCount
import com.biasnil.audioforge.data.albumGroups
import com.biasnil.audioforge.data.tracksInAlbum

/** Albums as a two-column grid of covers, like the iOS app. Long-press for Play next / Add to queue. */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable, on older Compose versions
@Composable
fun AlbumsTab(onOpenAlbum: (String) -> Unit, onRequestAudioPermission: () -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val albums = remember(tracks) { albumGroups(tracks) }
    if (albums.isEmpty()) {
        LibraryEmptyState(onRequestAudioPermission)
        return
    }
    val unknownAlbum = stringResource(R.string.unknown_album)
    val unknownArtist = stringResource(R.string.unknown_artist)
    val variousArtists = stringResource(R.string.various_artists)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(albums, key = { it.name }) { album ->
            val name = album.name.ifEmpty { unknownAlbum }
            Column(
                Modifier.combinedClickable(
                    onClick = { onOpenAlbum(album.name) },
                    onLongClick = { trackActions.showGroupMenu(name, tracksInAlbum(tracks, album.name)) },
                )
            ) {
                CoverImage(
                    track = artworkTrack(album.tracks),
                    decodeSize = 180.dp,
                    cornerRadius = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (val artist = albumArtist(album)) {
                        null -> unknownArtist
                        "" -> variousArtists
                        else -> artist
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Artists or Genres: one row per group with a cover (round for artists),
 * "N songs" (and "· M albums" for artists). Long-press a group for Play
 * next / Add to queue with [tracksOf] its songs.
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable, on older Compose versions
@Composable
fun GroupsTab(
    groups: List<TrackGroup>,
    unknownName: Int,
    onOpenGroup: (String) -> Unit,
    tracksOf: (TrackGroup) -> List<Track>,
    onRequestAudioPermission: () -> Unit,
    roundArtwork: Boolean = false,
    showAlbumCount: Boolean = false,
) {
    val trackActions = LocalTrackActions.current
    if (groups.isEmpty()) {
        LibraryEmptyState(onRequestAudioPermission)
        return
    }
    LazyColumn {
        items(groups, key = { it.name }) { group ->
            val name = group.name.ifEmpty { stringResource(unknownName) }
            Column(
                Modifier.combinedClickable(
                    onClick = { onOpenGroup(group.name) },
                    onLongClick = { trackActions.showGroupMenu(name, tracksOf(group)) },
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(
                        track = artworkTrack(group.tracks),
                        decodeSize = 44.dp,
                        cornerRadius = if (roundArtwork) 22.dp else 6.dp,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val songs = pluralStringResource(R.plurals.song_count, group.tracks.size, group.tracks.size)
                        val subtitle = if (showAlbumCount) {
                            val albums = albumCount(group.tracks)
                            songs + " · " + pluralStringResource(R.plurals.album_count, albums, albums)
                        } else {
                            songs
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** Whose cover stands for a group: the first song from an added folder with a cover file, else the first song. */
private fun artworkTrack(tracks: List<Track>): Track =
    tracks.firstOrNull { it.folderCoverUri != null } ?: tracks.first()
