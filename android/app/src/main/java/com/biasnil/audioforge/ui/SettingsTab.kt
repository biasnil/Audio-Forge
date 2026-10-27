package com.biasnil.audioforge.ui

import android.app.Activity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.annotation.StringRes
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.biasnil.audioforge.AppLanguage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.Appearance
import com.biasnil.audioforge.data.SecretBox
import com.biasnil.audioforge.data.libraryStats
import com.biasnil.audioforge.linkdownload.LinkDownloadFeature
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt

/** Settings' categories, in order; each opens a [SettingsSectionPage] (Language opens its picker). */
enum class SettingsSection(val key: String, @StringRes val title: Int, val icon: ImageVector) {
    Appearance("appearance", R.string.settings_appearance, AppIcons.Palette),
    Playback("playback", R.string.settings_playback, AppIcons.Play),
    Smart("smart", R.string.smart_options, AppIcons.Sparkle),
    Visualizer("visualizer", R.string.settings_visualizer, AppIcons.Waveform),
    Wallpaper("wallpaper", R.string.settings_wallpaper, AppIcons.Movie),
    Lyrics("lyrics", R.string.settings_lyrics, AppIcons.Lyrics),
    Library("library", R.string.settings_library, AppIcons.LibraryMusic),
    /** Download from link's smart title detection (the GitHub version only). */
    Downloads("downloads", R.string.settings_downloads, AppIcons.Download),
    Tabs("tabs", R.string.tabbar_section, AppIcons.Tab),
    About("about", R.string.settings_about, AppIcons.Info),
    ;

    /** False for a category this version of the app doesn't have. */
    val available: Boolean get() = this != Downloads || LinkDownloadFeature.AVAILABLE
}

/**
 * The Settings tab: Search settings, then a short list of categories, like
 * the phone's own Settings -- an icon, a name and what's inside -- with
 * Language first. Each category opens its own page, so no page is crowded.
 * Typing in the search shows matching settings instead; tapping one opens
 * its category at that setting, and Back comes back to the results.
 */
@Composable
fun SettingsTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    /** A search result was Language: it's highlighted on this page. */
    var focusLanguage by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SettingsSearchField(query, onQueryChange = {
            query = it
            focusLanguage = false
        })
        if (query.isNotBlank()) {
            SettingsSearchResults(query) { result ->
                if (result.sectionKey.isEmpty()) {
                    query = ""
                    focusLanguage = true
                } else {
                    onOpenPage(Page.SettingsSectionPage(result.sectionKey, result.id))
                }
            }
            return@Column
        }
        SettingsGroup {
            CompositionLocalProvider(LocalSettingFocus provides if (focusLanguage) "language" else null) {
                SettingAnchor("language") { LanguageRow() }
            }
        }
        SettingsGroup {
            SettingsSection.entries.filter { it.available }.forEachIndexed { index, section ->
                if (index > 0) SettingsGroupDivider()
                SettingsCategoryRow(
                    icon = section.icon,
                    title = stringResource(section.title),
                    summary = sectionSummary(section, tracks.size),
                    onClick = { onOpenPage(Page.SettingsSectionPage(section.key)) },
                )
            }
        }
    }
}

@Composable
private fun sectionSummary(section: SettingsSection, songs: Int): String = when (section) {
    SettingsSection.Appearance -> stringResource(R.string.settings_appearance_summary)
    SettingsSection.Playback -> stringResource(R.string.settings_playback_summary)
    SettingsSection.Smart -> stringResource(R.string.settings_smart_summary)
    SettingsSection.Visualizer -> stringResource(R.string.settings_visualizer_summary)
    SettingsSection.Wallpaper -> stringResource(R.string.settings_wallpaper_summary)
    SettingsSection.Lyrics -> stringResource(R.string.settings_lyrics_summary)
    SettingsSection.Library ->
        pluralStringResource(R.plurals.track_count, songs, songs) + " · " + stringResource(R.string.settings_library_summary)
    SettingsSection.Downloads -> stringResource(R.string.settings_downloads_summary)
    SettingsSection.Tabs -> stringResource(R.string.settings_tabs_summary)
    SettingsSection.About -> stringResource(R.string.settings_about_summary)
}

/**
 * One category's settings, under its own title. [focus] is the setting a
 * search result points at (see SettingAnchor); an id that no longer exists
 * just opens the category.
 */
@Composable
fun SettingsSectionPage(key: String, focus: String? = null, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val section = SettingsSection.entries.firstOrNull { it.key == key && it.available } ?: SettingsSection.Appearance
    CompositionLocalProvider(LocalSettingFocus provides focus) {
        SettingsSectionContent(section, onBack, onOpenPage)
    }
}

@Composable
private fun SettingsSectionContent(section: SettingsSection, onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    Scaffold(topBar = { DetailTopBar(stringResource(section.title), onBack) }) { innerPadding ->
        // The tab bar editor is a drag list of its own (it scrolls itself).
        if (section == SettingsSection.Tabs) {
            TabBarSettings(Modifier.padding(innerPadding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            when (section) {
                SettingsSection.Appearance -> AppearanceSettings(onOpenPage)
                SettingsSection.Playback -> PlaybackSettings()
                SettingsSection.Smart -> SmartSettings()
                SettingsSection.Visualizer -> VisualizerSettings(onOpenPage)
                SettingsSection.Wallpaper -> WallpaperSettings()
                SettingsSection.Lyrics -> LyricsSettings()
                SettingsSection.Library -> LibrarySettings(onOpenPage)
                SettingsSection.Downloads -> DownloadSettings()
                SettingsSection.Tabs -> Unit // above
                SettingsSection.About -> AboutSection(onOpenPage)
            }
        }
    }
}

/** A rounded card holding rows, like the More list. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) { content() }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** "[icon]  Playback / ReplayGain, crossfade...  >" */
@Composable
private fun SettingsCategoryRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An explanation under a setting: readable size, not the smallest text. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Library numbers (as on the desktop), refresh, duplicates, listening stats. */
@Composable
private fun LibrarySettings(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val stats = remember(tracks) { libraryStats(tracks) }

    Text(
        text = stringResource(R.string.settings_stats, stats.tracks, stats.albums, stats.artists, stats.genres),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SettingsNote(stringResource(R.string.tags_hint), Modifier.padding(top = 4.dp))
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingAnchor("library.refresh") {
            OutlinedButton(
                onClick = {
                    // Files may have been changed elsewhere: covers are read again too.
                    container.coverArt.clearAll()
                    container.library.refresh()
                },
                enabled = !scanning,
            ) {
                Text(stringResource(R.string.refresh_library))
            }
        }
        Spacer(Modifier.width(12.dp))
        SettingAnchor("library.duplicates") {
            OutlinedButton(onClick = { onOpenPage(Page.Duplicates) }) {
                Text(stringResource(R.string.duplicates_find))
            }
        }
        if (scanning) {
            Spacer(Modifier.width(16.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    SettingAnchor("library.stats") {
        SettingsLinkRow(stringResource(R.string.listening_stats)) { onOpenPage(Page.ListeningStats) }
    }
    SettingAnchor("library.covers") { FindMissingCoversRow() }
}

/** Light / dark, and the home-screen widgets. */
@Composable
private fun AppearanceSettings(onOpenPage: (Page) -> Unit) {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()

    SettingAnchor("appearance.mode") {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val options = Appearance.entries
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = settings.appearance == option,
                    onClick = { store.update { it.copy(appearance = option) } },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(
                        stringResource(
                            when (option) {
                                Appearance.System -> R.string.settings_appearance_system
                                Appearance.Light -> R.string.settings_appearance_light
                                Appearance.Dark -> R.string.settings_appearance_dark
                            }
                        )
                    )
                }
            }
        }
    }
    ThemeSettings()
    SettingAnchor("appearance.widgets") {
        SettingsLinkRow(stringResource(R.string.widgets_settings_row)) { onOpenPage(Page.Widgets) }
    }
}

/**
 * Language: the app's own language, or the phone's. Language names are
 * shown in their own language, so anyone can find theirs.
 */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    // Changing it recreates the screen, and so does changing it in the
    // phone's App languages settings, so reading it once here is enough.
    val current = remember { AppLanguage.current(context) }
    var choosing by remember { mutableStateOf(false) }
    val systemName = stringResource(R.string.language_system)

    SettingsCategoryRow(
        icon = AppIcons.Language,
        // Also says "Language" in English, so it can be found in a language you can't read.
        title = stringResource(R.string.settings_language).let { name -> if (name == "Language") name else "$name · Language" },
        summary = current?.let(AppLanguage::nativeName) ?: systemName,
        onClick = { choosing = true },
    )

    if (choosing) {
        val options = listOf<String?>(null) + AppLanguage.TAGS
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = tag == current, role = Role.RadioButton) {
                                    choosing = false
                                    if (tag != current) context.findActivity()?.let { AppLanguage.set(it, tag) }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(tag?.let(AppLanguage::nativeName) ?: systemName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** ReplayGain and crossfade (the desktop's ReplayGain button and Crossfade settings). */
@Composable
private fun PlaybackSettings() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()

    SettingAnchor("playback.replaygain") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_replaygain),
            info = stringResource(R.string.settings_replaygain_explanation),
            checked = settings.replayGainEnabled,
            onCheckedChange = { on -> store.update { it.copy(replayGainEnabled = on) } },
        )
    }
    SettingAnchor("playback.crossfade") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_crossfade),
            checked = settings.crossfadeEnabled,
            onCheckedChange = { on -> store.update { it.copy(crossfadeEnabled = on) } },
        )
    }
    val range = AppSettings.CROSSFADE_SECONDS_RANGE
    Text(
        text = stringResource(R.string.settings_crossfade_duration, settings.crossfadeSeconds),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Slider(
        value = settings.crossfadeSeconds.toFloat(),
        onValueChange = { value -> store.update { it.copy(crossfadeSeconds = value.roundToInt().coerceIn(range)) } },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1, // whole seconds
        enabled = settings.crossfadeEnabled,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SettingAnchor("playback.sleep_fade") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_sleep_fade),
            checked = settings.sleepFadeOut,
            onCheckedChange = { on -> store.update { it.copy(sleepFadeOut = on) } },
        )
    }
    SettingAnchor("playback.resume_long") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_resume_long),
            info = stringResource(R.string.settings_resume_long_explanation),
            checked = settings.rememberLongTrackPosition,
            onCheckedChange = { on -> store.update { it.copy(rememberLongTrackPosition = on) } },
        )
    }
}

/**
 * "Analyze Your Songs' Key and BPM" and the Smart Options it unlocks
 * (smart transitions, beat-matched crossfades, harmonic shuffle), as in the
 * iOS app's Settings.
 */
@Composable
private fun SmartSettings() {
    val container = LocalAppContainer.current
    val store = container.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val analysis by container.analysis.analysis.collectAsStateWithLifecycle()
    val progress by container.analyzer.progress.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val analyzed = remember(tracks, analysis) { container.analysis.analyzedCount(tracks) }

    val running = progress
    SettingAnchor("smart.analysis") {
        if (running != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.analysis_running, running.done, running.total), modifier = Modifier.weight(1f))
                    InfoButton(stringResource(R.string.analysis_background_note))
                    TextButton(onClick = container.analyzer::cancel) { Text(stringResource(R.string.analysis_stop)) }
                }
                LinearProgressIndicator(
                    progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = tracks.isNotEmpty() && !scanning) { container.analyzer.start(tracks) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.analysis_start), color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (analyzed == tracks.size) stringResource(R.string.analysis_all_done, tracks.size)
                    else stringResource(R.string.analysis_some_done, analyzed, tracks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SettingAnchor("smart.genres") { GenreDetectionRow() }

    val locked = !settings.analysisCompleted
    if (locked) {
        Text(
            text = stringResource(R.string.smart_locked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    SettingAnchor("smart.transitions") {
        SettingsSwitchRow(
            label = stringResource(R.string.smart_transitions),
            checked = settings.smartTransitions,
            enabled = !locked,
            onCheckedChange = { on -> store.update { it.copy(smartTransitions = on) } },
        )
    }
    SettingAnchor("smart.beat_match") {
        SettingsSwitchRow(
            label = stringResource(R.string.smart_beat_matched),
            checked = settings.beatMatchedCrossfade,
            enabled = !locked && settings.crossfadeEnabled,
            onCheckedChange = { on -> store.update { it.copy(beatMatchedCrossfade = on) } },
        )
    }
    SettingAnchor("smart.harmonic") {
        SettingsSwitchRow(
            label = stringResource(R.string.smart_harmonic),
            checked = settings.harmonicShuffle,
            enabled = !locked,
            onCheckedChange = { on -> store.update { it.copy(harmonicShuffle = on) } },
        )
    }
    Text(
        text = stringResource(R.string.smart_options_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * "Detect Genres": runs genre detection on the phone (started by hand), with
 * the same progress, Stop button and charging note as the key/BPM analysis.
 * The results show in the tag editor; they don't change the GENRE tags.
 */
@Composable
private fun GenreDetectionRow() {
    val container = LocalAppContainer.current
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val genres by container.genres.genres.collectAsStateWithLifecycle()
    val progress by container.genreAnalyzer.progress.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val detected = remember(tracks, genres) { container.genres.detectedCount(tracks) }

    val running = progress
    if (running != null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.genre_running, running.done, running.total), modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.analysis_background_note))
                TextButton(onClick = container.genreAnalyzer::cancel) { Text(stringResource(R.string.analysis_stop)) }
            }
            LinearProgressIndicator(
                progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = tracks.isNotEmpty() && !scanning) { container.genreAnalyzer.start(tracks) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.genre_start), color = MaterialTheme.colorScheme.primary)
            Text(
                text = if (detected == tracks.size) stringResource(R.string.genre_all_done, tracks.size)
                else stringResource(R.string.genre_some_done, detected, tracks.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The desktop's Video Wallpaper settings: on/off and opacity. Videos are chosen in the Wallpapers tab. */
@Composable
private fun WallpaperSettings() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()

    SettingAnchor("wallpaper.enabled") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_wallpaper_enabled),
            checked = settings.videoWallpaperEnabled,
            onCheckedChange = { on -> store.update { it.copy(videoWallpaperEnabled = on) } },
        )
    }
    SettingAnchor("wallpaper.opacity") {
        Text(
            text = stringResource(R.string.settings_wallpaper_opacity, settings.videoWallpaperOpacityPercent),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider(
            value = settings.videoWallpaperOpacityPercent.toFloat(),
            onValueChange = { value -> store.update { it.copy(videoWallpaperOpacityPercent = value.roundToInt().coerceIn(0, 100)) } },
            valueRange = 0f..100f,
            enabled = settings.videoWallpaperEnabled, // dimming a wallpaper that's off is meaningless
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Musixmatch API key. Held only in memory while editing (not in saved UI
 * state), and stored encrypted.
 */
@Composable
private fun LyricsSettings() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val savedKey = remember(settings.musixmatchKeyEncrypted) { SecretBox.decrypt(settings.musixmatchKeyEncrypted) }
    var keyText by remember(savedKey) { mutableStateOf(savedKey) }

    SettingAnchor("lyrics.lock_screen") {
        SettingsSwitchRow(
            label = stringResource(R.string.settings_lyrics_lock_screen),
            info = stringResource(R.string.settings_lyrics_lock_screen_explanation),
            checked = settings.lyricsOnNowPlaying,
            onCheckedChange = { on -> store.update { it.copy(lyricsOnNowPlaying = on) } },
        )
    }
    SettingAnchor("lyrics.musixmatch") {
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            placeholder = { Text(stringResource(R.string.settings_musixmatch_key_hint)) },
            // Where lyrics come from, and what the key is for.
            trailingIcon = { InfoButton(stringResource(R.string.settings_lyrics_explanation)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(
                enabled = keyText.trim() != savedKey,
                onClick = {
                    val encrypted = SecretBox.encrypt(keyText.trim())
                    store.update { it.copy(musixmatchKeyEncrypted = encrypted) }
                },
            ) { Text(stringResource(R.string.save)) }
            if (savedKey.isNotEmpty()) {
                TextButton(onClick = { store.update { it.copy(musixmatchKeyEncrypted = "") } }) {
                    Text(stringResource(R.string.settings_musixmatch_key_clear))
                }
            }
        }
    }
}

/** A row that opens a page (Listening stats, Widgets). */
@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    /** A longer explanation, behind an (i) next to the label. */
    info: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label (with its (i) right after it) takes all the room the switch doesn't,
        // so every switch lines up at the end and labels only wrap when they must.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = info == null),
            )
            if (info != null) InfoButton(info, title = label)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
