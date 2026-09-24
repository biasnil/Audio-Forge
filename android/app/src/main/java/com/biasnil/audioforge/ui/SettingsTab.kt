package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
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
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.SecretBox
import com.biasnil.audioforge.data.libraryStats
import kotlin.math.roundToInt

@Composable
fun SettingsTab() {
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
            if (scanning) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        HorizontalDivider()
        SectionHeader(stringResource(R.string.settings_appearance))
        SettingsSwitchRow(
            label = stringResource(R.string.settings_dark_theme),
            checked = settings.darkTheme,
            onCheckedChange = { dark -> container.store.update { it.copy(darkTheme = dark) } },
        )

        HorizontalDivider(Modifier.padding(top = 8.dp))
        PlaybackSettings()

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
        Spacer(Modifier.padding(bottom = 16.dp))
    }
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
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
