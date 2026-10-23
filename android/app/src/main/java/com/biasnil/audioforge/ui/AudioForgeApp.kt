package com.biasnil.audioforge.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.AppContainer
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.splitTabBar
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.artistGroups
import com.biasnil.audioforge.data.audioPermission
import com.biasnil.audioforge.data.genreGroups
import com.biasnil.audioforge.data.tracksByArtist
import com.biasnil.audioforge.data.tracksInAlbum
import com.biasnil.audioforge.data.tracksInGenre
import com.biasnil.audioforge.playback.PlaybackError
import kotlinx.coroutines.launch

@Composable
fun AudioForgeApp(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        var stack by rememberSaveable(stateSaver = PageStackSaver) { mutableStateOf(emptyList<Page>()) }
        val openPage: (Page) -> Unit = { page -> stack = stack + page }
        val closePage: () -> Unit = { stack = stack.dropLast(1) }

        val requestAudioPermission = rememberAudioPermissionRequest()
        val snackbarHostState = remember { SnackbarHostState() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val undoLabel = stringResource(R.string.undo)
        val trackActions = rememberTrackActions(
            onOpenPage = openPage,
            showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            showUndoable = { message, undo ->
                scope.launch {
                    val result = snackbarHostState.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) undo()
                }
            },
        )
        LaunchedEffect(Unit) {
            container.playback.errors.collect { error ->
                val message = when (error) {
                    is PlaybackError.Skipped -> context.getString(R.string.error_skipped, error.name)
                    is PlaybackError.Failed -> context.getString(R.string.error_cannot_play, error.name)
                }
                snackbarHostState.showSnackbar(message)
            }
        }

        CompositionLocalProvider(LocalTrackActions provides trackActions) {
            Box(Modifier.fillMaxSize()) {
                // The library stays composed underneath the pages, so its tab,
                // search text and scroll position are still there on the way back.
                LibraryScreen(onOpenPage = openPage, onRequestAudioPermission = requestAudioPermission)
                // After the library's own back handlers (leave the More tab, end
                // a selection): the last one registered wins, and an open page
                // has to close first. A page's own handlers come later still.
                BackHandler(enabled = stack.isNotEmpty(), onBack = closePage)

                stack.lastOrNull()?.let { page ->
                    Surface(Modifier.fillMaxSize()) {
                        PageContent(page, onOpenPage = openPage, onBack = closePage)
                    }
                }

                // At the top, like the iOS app's notice banner (clear of the tab bar and mini player).
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PageContent(page: Page, onOpenPage: (Page) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    when (page) {
        Page.NowPlaying -> NowPlayingScreen(onBack = onBack, onOpenPage = onOpenPage)
        Page.UpNext -> UpNextPage(onBack = onBack)
        Page.FullLyrics -> FullLyricsPage(onBack = onBack)
        is Page.SmartPlaylistDetail -> SmartPlaylistPage(page.id, onBack = onBack, onOpenPage = onOpenPage)
        is Page.SmartPlaylistEdit -> SmartPlaylistEditorPage(page.id, onBack = onBack, onOpenPage = onOpenPage)
        Page.Duplicates -> DuplicatesPage(onBack = onBack)
        is Page.AlbumDetail -> GroupPage(
            title = page.name.ifEmpty { stringResource(R.string.unknown_album) },
            tracks = remember(tracks, page.name) { tracksInAlbum(tracks, page.name) },
            onBack = onBack,
            onOpenPage = onOpenPage,
        )
        is Page.ArtistDetail -> GroupPage(
            title = page.name.ifEmpty { stringResource(R.string.unknown_artist) },
            tracks = remember(tracks, page.name) { tracksByArtist(tracks, page.name) },
            onBack = onBack,
            onOpenPage = onOpenPage,
        )
        is Page.GenreDetail -> GroupPage(
            title = page.name.ifEmpty { stringResource(R.string.unknown_genre) },
            tracks = remember(tracks, page.name) { tracksInGenre(tracks, page.name) },
            onBack = onBack,
            onOpenPage = onOpenPage,
        )
        is Page.PlaylistDetail -> PlaylistPage(page.id, onBack = onBack, onOpenPage = onOpenPage)
        is Page.AddTracks -> AddTracksPage(page.playlistId, onBack = onBack)
        is Page.EditTags -> TagEditorPage(page.trackUri, onBack = onBack)
        is Page.WallpaperTracks -> WallpaperTracksPage(page.entryId, page.newVideoUri, onBack = onBack)
        Page.Terms -> TermsPage(onBack = onBack)
        Page.Privacy -> PrivacyPage(onBack = onBack)
        Page.Acknowledgements -> AcknowledgementsPage(onBack = onBack, onOpenPage = onOpenPage)
        is Page.LicenseText -> LicenseTextPage(page.id, onBack = onBack)
        is Page.MediaTracks -> MediaTracksPage(page.entryId, page.newUri, onBack = onBack)
        is Page.PodcastShow -> PodcastShowPage(page.name, onBack = onBack)
        Page.Widgets -> WidgetsPage(onBack = onBack)
        Page.ListeningStats -> ListeningStatsPage(onBack = onBack)
        is Page.SettingsSectionPage -> SettingsSectionPage(page.key, onBack = onBack, onOpenPage = onOpenPage)
        Page.HeadphonePicker -> HeadphonePickerPage(onBack = onBack)
        Page.PodcastSearch -> PodcastSearchPage(onBack = onBack, onOpenPage = onOpenPage)
        is Page.PodcastFeed -> PodcastFeedPage(page.feedUrl, page.artworkUrl, onBack = onBack)
    }
}

/**
 * The library: the iOS app's tab bar -- the four tabs chosen in Settings ->
 * Tab bar, then "More" listing the rest -- the mini player above it, and
 * each tab under its large title.
 */
@Composable
private fun LibraryScreen(onOpenPage: (Page) -> Unit, onRequestAudioPermission: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()

    val (barTabs, moreTabs) = remember(settings.tabBar, settings.hiddenTabs) {
        val (bar, more) = splitTabBar(LibraryTab.entries.map { it.name }, settings.tabBar, hiddenTabNames(settings.hiddenTabs))
        bar.map(LibraryTab::valueOf) to more.map(LibraryTab::valueOf)
    }

    // Songs first, like the iOS app.
    var selectedKey by rememberSaveable { mutableStateOf(LibraryTab.Songs.name) }
    /** The tab opened from the More list, if any. */
    var moreTabName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedKeys = barTabs.map { it.name } + if (moreTabs.isNotEmpty()) listOf(MORE_KEY) else emptyList()
    if (selectedKey !in selectedKeys) selectedKey = selectedKeys.first()
    val moreTab = moreTabs.firstOrNull { it.name == moreTabName }
    BackHandler(enabled = selectedKey == MORE_KEY && moreTab != null) { moreTabName = null }

    val songsState = remember { SongsTabState() }
    val shownSongs = rememberShownSongs(songsState)
    BackHandler(enabled = songsState.selecting) { songsState.endSelecting() }

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayerBar(state = playback, onOpenNowPlaying = { onOpenPage(Page.NowPlaying) })
                AppTabBar(
                    items = barTabs.map { it.name to it } + if (moreTabs.isNotEmpty()) listOf(MORE_KEY to null) else emptyList(),
                    selected = selectedKey,
                    onSelect = { key ->
                        if (key == MORE_KEY && selectedKey == MORE_KEY) moreTabName = null // tap again: back to the list
                        selectedKey = key
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val tab = if (selectedKey == MORE_KEY) moreTab else LibraryTab.valueOf(selectedKey)
            if (tab == null) {
                MoreList(moreTabs, onOpen = { moreTabName = it.name })
                return@Column
            }
            val selectingSongs = tab == LibraryTab.Songs && songsState.selecting
            TabHeader(
                title = if (selectingSongs) stringResource(R.string.selected_count, songsState.selection.size)
                else stringResource(tab.title),
                onBack = if (selectedKey == MORE_KEY) ({ moreTabName = null }) else null,
                // A gear next to the title, so Settings is clearly Settings.
                icon = if (tab == LibraryTab.Settings) tab.icon else null,
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                when (tab) {
                    LibraryTab.Songs -> SongsHeaderActions(songsState, shownSongs)
                    LibraryTab.Playlists -> PlaylistsHeaderActions(onOpenPage)
                    LibraryTab.Podcasts -> PodcastsHeaderActions(onOpenPage)
                    else -> Unit
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (tab) {
                    LibraryTab.Songs -> TracksTab(songsState, onRequestAudioPermission)
                    LibraryTab.Albums -> AlbumsTab(
                        onOpenAlbum = { onOpenPage(Page.AlbumDetail(it)) },
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                    LibraryTab.Artists -> GroupsTab(
                        groups = remember(tracks) { artistGroups(tracks) },
                        unknownName = R.string.unknown_artist,
                        onOpenGroup = { onOpenPage(Page.ArtistDetail(it)) },
                        tracksOf = { tracksByArtist(tracks, it.name) },
                        onRequestAudioPermission = onRequestAudioPermission,
                        roundArtwork = true,
                        showAlbumCount = true,
                    )
                    LibraryTab.Genres -> GroupsTab(
                        groups = remember(tracks) { genreGroups(tracks) },
                        unknownName = R.string.unknown_genre,
                        onOpenGroup = { onOpenPage(Page.GenreDetail(it)) },
                        tracksOf = { tracksInGenre(tracks, it.name) },
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                    LibraryTab.Folders -> FoldersTab(onRequestAudioPermission)
                    LibraryTab.Playlists -> PlaylistsTab(onOpenPage)
                    LibraryTab.Equalizer -> EqualizerTab()
                    LibraryTab.Effects -> EffectsTab(onOpenPage)
                    LibraryTab.Settings -> SettingsTab(onOpenPage)
                    LibraryTab.Wallpapers -> WallpapersTab(onOpenPage)
                    LibraryTab.Podcasts -> PodcastsTab(onOpenPage)
                    LibraryTab.Radio -> RadioTab()
                }
            }
        }
    }
}

private const val MORE_KEY = "More"

/** The More tab: the tabs that don't fit in the bar. */
@Composable
private fun MoreList(tabs: List<LibraryTab>, onOpen: (LibraryTab) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.tab_more))
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            tabs.forEachIndexed { index, tab ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(tab) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(tab.title), modifier = Modifier.weight(1f))
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (index < tabs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

/**
 * Asks for [audioPermission] (READ_MEDIA_AUDIO, or storage on Android 12). Asked once at startup if the phone library is
 * on and access isn't granted yet; after that, only from the "Allow access"
 * buttons (Android itself stops showing the prompt after two refusals).
 */
@Composable
private fun rememberAudioPermissionRequest(): () -> Unit {
    val library = LocalAppContainer.current.library
    val settings = LocalAppContainer.current.store.settings
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        library.onAppResumed() // rescans if access changed
    }
    val request = remember(launcher) { { launcher.launch(audioPermission) } }
    var askedAtStartup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!askedAtStartup && settings.value.includePhoneLibrary && !library.hasAudioPermission.value) {
            askedAtStartup = true
            request()
        }
    }
    return request
}
