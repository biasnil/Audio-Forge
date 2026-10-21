package com.biasnil.audioforge.ui

import android.app.Activity
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
import kotlin.math.roundToInt

@Composable
fun SettingsTab(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val scanning by container.library.scanning.collectAsStateWithLifecycle()
    val stats = remember(tracks) { libraryStats(tracks) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // --- Library (same stats as the desktop Settings tab) ---
        SectionHeader(stringResource(R.string.settings_library))
        Text(
            text = stringResource(R.string.settings_stats, stats.tracks, stats.albums, stats.artists, stats.genres),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(R.string.tags_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = container.library::refresh, enabled = !scanning) {
                Text(stringResource(R.string.refresh_library))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { onOpenPage(Page.Duplicates) }) {
                Text(stringResource(R.string.duplicates_find))
            }
            if (scanning) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        HorizontalDivider()
        SectionHeader(stringResource(R.string.settings_appearance))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val options = Appearance.entries
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = settings.appearance == option,
                    onClick = { container.store.update { it.copy(appearance = option) } },
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
        LanguageRow()

        HorizontalDivider()
        PlaybackSettings()

        HorizontalDivider(Modifier.padding(top = 8.dp))
        SmartSettings()

        HorizontalDivider(Modifier.padding(top = 8.dp))
        WallpaperSettings()

        HorizontalDivider(Modifier.padding(top = 8.dp))
        LyricsSettings()

        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionHeader(stringResource(R.string.settings_visible_tabs))
        LibraryTab.entries.filter { it.hideable }.forEach { tab ->
            val visible = tab.name !in settings.hiddenTabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        container.store.update {
                            it.copy(hiddenTabs = if (visible) it.hiddenTabs + tab.name else it.hiddenTabs - tab.name)
                        }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = visible, onCheckedChange = null)
                Text(stringResource(tab.title))
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
        AboutSection(onOpenPage)
        Spacer(Modifier.padding(bottom = 16.dp))
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { choosing = true }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_language), modifier = Modifier.weight(1f))
        Text(
            text = current?.let(AppLanguage::nativeName) ?: systemName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

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

    SectionHeader(stringResource(R.string.settings_playback))
    SettingsSwitchRow(
        label = stringResource(R.string.settings_replaygain),
        checked = settings.replayGainEnabled,
        onCheckedChange = { on -> store.update { it.copy(replayGainEnabled = on) } },
    )
    Text(
        text = stringResource(R.string.settings_replaygain_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SettingsSwitchRow(
        label = stringResource(R.string.settings_crossfade),
        checked = settings.crossfadeEnabled,
        onCheckedChange = { on -> store.update { it.copy(crossfadeEnabled = on) } },
    )
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
    SettingsSwitchRow(
        label = stringResource(R.string.settings_sleep_fade),
        checked = settings.sleepFadeOut,
        onCheckedChange = { on -> store.update { it.copy(sleepFadeOut = on) } },
    )
    SettingsSwitchRow(
        label = stringResource(R.string.settings_resume_long),
        checked = settings.rememberLongTrackPosition,
        onCheckedChange = { on -> store.update { it.copy(rememberLongTrackPosition = on) } },
    )
    Text(
        text = stringResource(R.string.settings_resume_long_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
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

    SectionHeader(stringResource(R.string.smart_options))
    val running = progress
    if (running != null) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analysis_running, running.done, running.total), modifier = Modifier.weight(1f))
                TextButton(onClick = container.analyzer::cancel) { Text(stringResource(R.string.analysis_stop)) }
            }
            LinearProgressIndicator(
                progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.analysis_background_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    GenreDetectionRow()

    val locked = !settings.analysisCompleted
    if (locked) {
        Text(
            text = stringResource(R.string.smart_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    SettingsSwitchRow(
        label = stringResource(R.string.smart_transitions),
        checked = settings.smartTransitions,
        enabled = !locked,
        onCheckedChange = { on -> store.update { it.copy(smartTransitions = on) } },
    )
    SettingsSwitchRow(
        label = stringResource(R.string.smart_beat_matched),
        checked = settings.beatMatchedCrossfade,
        enabled = !locked && settings.crossfadeEnabled,
        onCheckedChange = { on -> store.update { it.copy(beatMatchedCrossfade = on) } },
    )
    SettingsSwitchRow(
        label = stringResource(R.string.smart_harmonic),
        checked = settings.harmonicShuffle,
        enabled = !locked,
        onCheckedChange = { on -> store.update { it.copy(harmonicShuffle = on) } },
    )
    Text(
        text = stringResource(R.string.smart_options_footer),
        style = MaterialTheme.typography.bodySmall,
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
                TextButton(onClick = container.genreAnalyzer::cancel) { Text(stringResource(R.string.analysis_stop)) }
            }
            LinearProgressIndicator(
                progress = { running.done.toFloat() / running.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.analysis_background_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
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
                style = MaterialTheme.typography.bodySmall,
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

    SectionHeader(stringResource(R.string.settings_wallpaper))
    SettingsSwitchRow(
        label = stringResource(R.string.settings_wallpaper_enabled),
        checked = settings.videoWallpaperEnabled,
        onCheckedChange = { on -> store.update { it.copy(videoWallpaperEnabled = on) } },
    )
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

    SectionHeader(stringResource(R.string.settings_lyrics))
    SettingsSwitchRow(
        label = stringResource(R.string.settings_lyrics_lock_screen),
        checked = settings.lyricsOnNowPlaying,
        onCheckedChange = { on -> store.update { it.copy(lyricsOnNowPlaying = on) } },
    )
    Text(
        text = stringResource(R.string.settings_lyrics_lock_screen_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        text = stringResource(R.string.settings_lyrics_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    OutlinedTextField(
        value = keyText,
        onValueChange = { keyText = it },
        placeholder = { Text(stringResource(R.string.settings_musixmatch_key_hint)) },
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

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
