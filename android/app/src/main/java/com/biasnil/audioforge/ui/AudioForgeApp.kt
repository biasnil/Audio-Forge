package com.biasnil.audioforge.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.biasnil.audioforge.data.albumGroups
import com.biasnil.audioforge.data.artistGroups
import com.biasnil.audioforge.data.tracksByArtist
import com.biasnil.audioforge.data.tracksInAlbum

@Composable
fun AudioForgeApp(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        var stack by rememberSaveable(stateSaver = PageStackSaver) { mutableStateOf(emptyList<Page>()) }
        val openPage: (Page) -> Unit = { page -> stack = stack + page }
        val closePage: () -> Unit = { stack = stack.dropLast(1) }
        BackHandler(enabled = stack.isNotEmpty(), onBack = closePage)

        val requestAudioPermission = rememberAudioPermissionRequest()
        val snackbarHostState = remember { SnackbarHostState() }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            container.playback.errors.collect { fileName ->
                snackbarHostState.showSnackbar(context.getString(R.string.error_cannot_play, fileName))
            }
        }

        Box(Modifier.fillMaxSize()) {
            // The library stays composed underneath the pages, so its tab,
            // search text and scroll position are still there on the way back.
            LibraryScreen(onOpenPage = openPage, onRequestAudioPermission = requestAudioPermission)

            stack.lastOrNull()?.let { page ->
                Surface(Modifier.fillMaxSize()) {
                    PageContent(page, onOpenPage = openPage, onBack = closePage)
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp), // above the mini player bar
            )
        }
    }
}

@Composable
private fun PageContent(page: Page, onOpenPage: (Page) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    when (page) {
        Page.NowPlaying -> NowPlayingScreen(onBack = onBack)
        is Page.AlbumDetail -> GroupPage(
            title = page.name.ifEmpty { stringResource(R.string.unknown_album) },
            tracks = remember(tracks, page.name) { tracksInAlbum(tracks, page.name) },
            onBack = onBack,
        )
        is Page.ArtistDetail -> GroupPage(
            title = page.name.ifEmpty { stringResource(R.string.unknown_artist) },
            tracks = remember(tracks, page.name) { tracksByArtist(tracks, page.name) },
            onBack = onBack,
        )
        is Page.PlaylistDetail -> PlaylistPage(page.id, onBack = onBack, onOpenPage = onOpenPage)
        is Page.AddTracks -> AddTracksPage(page.playlistId, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(onOpenPage: (Page) -> Unit, onRequestAudioPermission: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()

    val visibleTabs = LibraryTab.entries.filter { !it.hideable || it.name !in settings.hiddenTabs }
    // Tracks first, same as the desktop app's startup tab.
    var selectedTabName by rememberSaveable { mutableStateOf(LibraryTab.Tracks.name) }
    val selectedTab = visibleTabs.firstOrNull { it.name == selectedTabName } ?: visibleTabs.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
        bottomBar = {
            MiniPlayerBar(state = playback, onOpenNowPlaying = { onOpenPage(Page.NowPlaying) })
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = visibleTabs.indexOf(selectedTab),
                edgePadding = 0.dp,
            ) {
                visibleTabs.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTabName = tab.name },
                        text = { Text(stringResource(tab.title)) },
                    )
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    LibraryTab.Tracks -> TracksTab(onRequestAudioPermission)
                    LibraryTab.Albums -> GroupsTab(
                        groups = remember(tracks) { albumGroups(tracks) },
                        unknownName = R.string.unknown_album,
                        onOpenGroup = { onOpenPage(Page.AlbumDetail(it)) },
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                    LibraryTab.Artists -> GroupsTab(
                        groups = remember(tracks) { artistGroups(tracks) },
                        unknownName = R.string.unknown_artist,
                        onOpenGroup = { onOpenPage(Page.ArtistDetail(it)) },
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                    LibraryTab.Folders -> FoldersTab(onRequestAudioPermission)
                    LibraryTab.Playlists -> PlaylistsTab(onOpenPage)
                    LibraryTab.Equalizer -> EqualizerTab()
                    LibraryTab.Settings -> SettingsTab()
                    LibraryTab.Wallpapers -> ComingInStage(selectedTab)
                }
            }
        }
    }
}

@Composable
private fun ComingInStage(tab: LibraryTab) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CenteredText(stringResource(R.string.tab_coming_in_stage, stringResource(tab.title), tab.comingInStage ?: 0))
    }
}

/**
 * Asks for READ_MEDIA_AUDIO. Asked once at startup if the phone library is
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
    val request = remember(launcher) { { launcher.launch(Manifest.permission.READ_MEDIA_AUDIO) } }
    var askedAtStartup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!askedAtStartup && settings.value.includePhoneLibrary && !library.hasAudioPermission.value) {
            askedAtStartup = true
            request()
        }
    }
    return request
}
