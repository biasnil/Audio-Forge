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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.libraryStats

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
