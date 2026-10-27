package com.biasnil.audioforge.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biasnil.audioforge.BuildConfig
import com.biasnil.audioforge.R
import com.biasnil.audioforge.linkdownload.LinkDownloadFeature
import kotlinx.coroutines.delay

/**
 * One setting Settings search can find: a stable [id] (also the
 * SettingAnchor around the real control), where it is, its name and a
 * short summary from the app's strings (so it's found in every language),
 * and extra English words people type for it.
 */
private class SettingEntry(
    val id: String,
    val section: SettingsSection?,
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val aliases: List<String> = emptyList(),
    /** False where the setting doesn't exist in this version of the app. */
    val available: Boolean = true,
)

/**
 * Every searchable setting, in the order results tie in. A new setting
 * gets an entry here and a SettingAnchor with the same id around its
 * control (SettingsCatalogTest checks the ids are unique).
 */
private val SETTINGS_CATALOG: List<SettingEntry> = listOf(
    SettingEntry("language", null, R.string.settings_language, aliases = listOf("language", "translate", "english", "langue", "bahasa", "语言", "語言")),
    // Appearance
    SettingEntry("appearance.mode", SettingsSection.Appearance, R.string.settings_dark_theme, R.string.settings_appearance_summary, listOf("dark mode", "light mode", "night", "theme")),
    SettingEntry("appearance.pure_black", SettingsSection.Appearance, R.string.theme_pure_black, aliases = listOf("oled", "amoled", "black", "dark")),
    SettingEntry("appearance.colours", SettingsSection.Appearance, R.string.theme_colours, aliases = listOf("color", "colour", "accent", "material you", "wallpaper colours")),
    SettingEntry("appearance.widgets", SettingsSection.Appearance, R.string.widgets_settings_row, aliases = listOf("widget", "home screen")),
    // Playback
    SettingEntry("playback.replaygain", SettingsSection.Playback, R.string.settings_replaygain, R.string.settings_replaygain_explanation, listOf("volume", "loudness", "normalize", "level")),
    SettingEntry("playback.crossfade", SettingsSection.Playback, R.string.settings_crossfade, aliases = listOf("fade", "transition", "gap", "mix")),
    SettingEntry("playback.sleep_fade", SettingsSection.Playback, R.string.settings_sleep_fade, aliases = listOf("sleep timer", "timer")),
    SettingEntry("playback.resume_long", SettingsSection.Playback, R.string.settings_resume_long, R.string.settings_resume_long_explanation, listOf("resume", "continue", "position", "remember")),
    // Smart
    SettingEntry("smart.analysis", SettingsSection.Smart, R.string.analysis_start, aliases = listOf("key", "bpm", "tempo", "analyze", "analyse")),
    SettingEntry("smart.genres", SettingsSection.Smart, R.string.genre_start, aliases = listOf("genre", "detect")),
    SettingEntry("smart.transitions", SettingsSection.Smart, R.string.smart_transitions, aliases = listOf("gapless", "silence")),
    SettingEntry("smart.beat_match", SettingsSection.Smart, R.string.smart_beat_matched, aliases = listOf("beat", "dj", "tempo")),
    SettingEntry("smart.harmonic", SettingsSection.Smart, R.string.smart_harmonic, aliases = listOf("shuffle", "key")),
    // Visualizer
    SettingEntry("visualizer.style", SettingsSection.Visualizer, R.string.settings_visualizer, R.string.settings_visualizer_summary, listOf("animation", "bars", "spectrum", "visualiser")),
    SettingEntry("visualizer.custom_media", SettingsSection.Visualizer, R.string.custom_media, aliases = listOf("gif", "video", "animation")),
    // Wallpaper
    SettingEntry("wallpaper.enabled", SettingsSection.Wallpaper, R.string.settings_wallpaper_enabled, R.string.settings_wallpaper_summary, listOf("background", "video")),
    SettingEntry("wallpaper.opacity", SettingsSection.Wallpaper, R.string.settings_wallpaper_opacity_title, aliases = listOf("dim", "transparency", "brightness")),
    // Lyrics
    SettingEntry("lyrics.lock_screen", SettingsSection.Lyrics, R.string.settings_lyrics_lock_screen, aliases = listOf("lyrics", "android auto", "car", "notification")),
    SettingEntry("lyrics.musixmatch", SettingsSection.Lyrics, R.string.settings_musixmatch_key_hint, aliases = listOf("lyrics", "api key", "musixmatch")),
    // Library
    SettingEntry("library.refresh", SettingsSection.Library, R.string.refresh_library, aliases = listOf("rescan", "scan", "reload", "missing songs")),
    SettingEntry("library.duplicates", SettingsSection.Library, R.string.duplicates_find, aliases = listOf("duplicate", "same song", "copies")),
    SettingEntry("library.stats", SettingsSection.Library, R.string.listening_stats, aliases = listOf("statistics", "history", "most played", "time")),
    SettingEntry("library.length", SettingsSection.Library, R.string.length_skip, aliases = listOf("short", "long", "ringtone", "sounds", "missing songs", "length", "seconds", "minutes")),
    SettingEntry("library.cleanup", SettingsSection.Library, R.string.cleanup_title, aliases = listOf("clean", "rename", "fix names", "tidy", "official video", "lyrics", "titles")),
    SettingEntry("library.covers", SettingsSection.Library, R.string.covers_find, aliases = listOf("covers", "artwork", "album art", "pictures")),
    // Download from link (GitHub version only)
    SettingEntry("downloads.smart_titles", SettingsSection.Downloads, R.string.downloads_smart_titles, R.string.downloads_smart_titles_summary, listOf("title", "artist", "rename", "clean", "download", "official video"), LinkDownloadFeature.AVAILABLE),
    SettingEntry("downloads.order", SettingsSection.Downloads, R.string.downloads_title_order, aliases = listOf("artist first", "title first", "swap"), available = LinkDownloadFeature.AVAILABLE),
    SettingEntry("downloads.phrases", SettingsSection.Downloads, R.string.downloads_phrases, aliases = listOf("brackets", "remove", "official video", "lyrics"), available = LinkDownloadFeature.AVAILABLE),
    // Tabs
    SettingEntry("tabs.bar", SettingsSection.Tabs, R.string.tabbar_section, R.string.settings_tabs_summary, listOf("tabs", "tab bar", "more", "hide", "reorder", "menu")),
    // About
    SettingEntry("about.version", SettingsSection.About, R.string.about_version, aliases = listOf("version", "update")),
    SettingEntry("about.auto_update", SettingsSection.About, R.string.update_auto, aliases = listOf("update", "new version"), available = BuildConfig.UPDATE_CHECK_ENABLED),
    SettingEntry("about.report", SettingsSection.About, R.string.about_report, aliases = listOf("bug", "feedback", "problem", "contact", "email")),
    SettingEntry("about.privacy", SettingsSection.About, R.string.about_privacy, aliases = listOf("privacy", "data")),
    SettingEntry("about.terms", SettingsSection.About, R.string.about_terms, aliases = listOf("terms", "conditions")),
    SettingEntry("about.acknowledgements", SettingsSection.About, R.string.about_acknowledgements, aliases = listOf("licence", "license", "open source", "credits")),
)

/** The catalog's ids (tests: unique, and each with a destination). */
internal fun settingsCatalogIds(): List<String> = SETTINGS_CATALOG.map { it.id }

/** The catalog in the app's current language, without settings this version doesn't have. */
private fun searchableSettings(context: Context): List<SearchableSetting> =
    SETTINGS_CATALOG.filter { it.available && (it.section == null || it.section.available) }.map { entry ->
        SearchableSetting(
            id = entry.id,
            sectionKey = entry.section?.key.orEmpty(),
            title = context.getString(entry.title),
            sectionTitle = entry.section?.let { context.getString(it.title) }.orEmpty(),
            summary = entry.summary?.let(context::getString).orEmpty(),
            aliases = entry.aliases,
        )
    }

/**
 * Search settings, at the top of the Settings tab. [query] is the
 * tab's own (kept while a result is open, so Back finds it as it was).
 */
@Composable
fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.settings_search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The settings matching [query]: name, where it is and what it does; tap one to go to it. */
@Composable
fun SettingsSearchResults(query: String, onOpen: (SearchableSetting) -> Unit) {
    val context = LocalContext.current
    // Rebuilt when the language changes (the configuration does).
    val configuration = LocalConfiguration.current
    val catalog = remember(configuration) { searchableSettings(context) }
    val results = remember(catalog, query) { searchSettings(catalog, query) }
    if (results.isEmpty()) {
        CenteredText(
            stringResource(R.string.settings_search_none, query.trim()),
            Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    Column {
        results.forEach { result ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(result) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 32.dp, vertical = 10.dp)
            ) {
                Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val where = listOf(result.sectionTitle.ifEmpty { stringResource(R.string.tab_settings) }, result.summary)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                Text(
                    where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The setting a search result opened its section for (SettingAnchor highlights it). */
val LocalSettingFocus = staticCompositionLocalOf<String?> { null }

/**
 * Wraps a setting's control. When it's the one a search result opened,
 * it's scrolled into view and tinted for a moment -- once, without
 * changing it. Its name says where you are; the tint is only a hint.
 */
@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester, on older Compose versions
@Composable
fun SettingAnchor(id: String, content: @Composable () -> Unit) {
    if (LocalSettingFocus.current != id) {
        Column { content() }
        return
    }
    val requester = remember { BringIntoViewRequester() }
    // Saved, so coming back to the page (or rotating it) doesn't scroll again.
    var highlighted by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (!highlighted) return@LaunchedEffect
        delay(FOCUS_DELAY_MS) // the page's first layout
        requester.bringIntoView()
        delay(HIGHLIGHT_MS)
        highlighted = false
    }
    val tint by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        label = "settingHighlight",
    )
    Column(
        Modifier
            .bringIntoViewRequester(requester)
            .background(tint)
    ) { content() }
}

private const val FOCUS_DELAY_MS = 250L
private const val HIGHLIGHT_MS = 1_800L
