package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.PodcastShow
import com.biasnil.audioforge.data.SongStats
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.formatTime
import com.biasnil.audioforge.data.podcastShows

/** The shows, A-Z, from the podcast folders (see Podcasts.kt). */
@Composable
private fun rememberPodcastShows(): List<PodcastShow> {
    val container = LocalAppContainer.current
    val podcasts by container.library.podcasts.collectAsStateWithLifecycle()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    return remember(podcasts, settings.podcastFolders) { podcastShows(podcasts, container.library::podcastPlace) }
}

/**
 * The Podcasts tab: songs from folders marked Podcast in the Folders tab,
 * one row per show (a subfolder, else the Album tag), with how many
 * episodes are still unplayed.
 */
@Composable
fun PodcastsTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val shows = rememberPodcastShows()
    val stats by container.songData.stats.collectAsStateWithLifecycle()

    if (shows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            CenteredText(stringResource(R.string.podcasts_empty))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shows, key = { it.name }) { show ->
            val unplayed = show.episodes.count { stats[it.uri]?.played != true }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPage(Page.PodcastShow(show.name)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(show.episodes.first(), decodeSize = 56.dp, modifier = Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(show.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = stringResource(
                            R.string.podcast_show_summary,
                            pluralStringResource(R.plurals.podcast_episode_count, show.episodes.size, show.episodes.size),
                            unplayed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(start = 84.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * One show's episodes, newest first. Each says whether it's played or how
 * much is left; tapping plays from it (episodes continue where they were
 * left); long-press marks it played or unplayed.
 */
@Composable
fun PodcastShowPage(name: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val trackActions = LocalTrackActions.current
    val shows = rememberPodcastShows()
    val stats by container.songData.stats.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val show = shows.firstOrNull { it.name == name }
    if (show == null) {
        LaunchedEffect(Unit) { onBack() } // folder no longer a podcast, or emptied
        return
    }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = { DetailTopBar(show.name, onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding)) {
            itemsIndexed(show.episodes, key = { _, track -> track.uri }) { index, track ->
                val played = stats[track.uri]?.played == true
                TrackRow(
                    track = track,
                    isCurrent = track.uri == playback.current?.uri,
                    onClick = { container.playback.playQueue(show.episodes, index, from = show.name) },
                    onLongClick = { menuFor = track.uri },
                    extraLine = episodeStatus(track, stats[track.uri]),
                    trailing = if (played) ({
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = stringResource(R.string.podcast_played),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }) else null,
                )
            }
        }
    }

    menuFor?.let { uri ->
        val track = show.episodes.firstOrNull { it.uri == uri }
        if (track == null) {
            menuFor = null
            return@let
        }
        val played = stats[uri]?.played == true
        MenuDialog(title = track.title, onDismiss = { menuFor = null }) { close ->
            MenuDialogButton(stringResource(if (played) R.string.podcast_mark_unplayed else R.string.podcast_mark_played)) {
                close()
                container.songData.setPlayed(uri, !played)
            }
            MenuDialogButton(stringResource(R.string.more_options)) {
                close()
                trackActions.showMenu(track)
            }
        }
    }
}

/** "Played", "12:40 left" (where it was left), or nothing. */
@Composable
private fun episodeStatus(track: Track, stats: SongStats?): String? = when {
    stats?.played == true -> stringResource(R.string.podcast_played)
    stats?.resumePositionMs != null && track.durationMs > 0 ->
        stringResource(R.string.podcast_time_left, formatTime(track.durationMs - stats.resumePositionMs))
    else -> null
}
