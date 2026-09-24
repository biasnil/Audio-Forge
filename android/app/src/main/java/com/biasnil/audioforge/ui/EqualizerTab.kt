package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.audio.EqualizerPresets
import com.biasnil.audioforge.audio.EqualizerSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The desktop's Equalizer tab: 10 bands, presets, post-gain, reset. Bands
 * are horizontal sliders here (easier to use on a phone than 10 thin
 * vertical ones). Changes apply to the music immediately, including
 * during a crossfade.
 */
@Composable
fun EqualizerTab() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val enabled = settings.eqEnabled

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(stringResource(R.string.tab_equalizer), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { on -> store.update { it.copy(eqEnabled = on) } })
        }

        PresetMenu(
            bandGains = settings.eqBandGainsDb,
            enabled = enabled,
            onApply = { gains -> store.update { it.copy(eqBandGainsDb = gains) } },
        )

        repeat(EqualizerSettings.BAND_COUNT) { band ->
            GainRow(
                label = bandLabel(band),
                gainDb = settings.eqBandGainsDb.getOrElse(band) { 0f },
                enabled = enabled,
                onGainChange = { db ->
                    store.update { s ->
                        s.copy(eqBandGainsDb = s.eqBandGainsDb.mapIndexed { i, old -> if (i == band) db else old })
                    }
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        GainRow(
            label = stringResource(R.string.eq_post_gain),
            gainDb = settings.eqPostGainDb,
            enabled = enabled,
            onGainChange = { db -> store.update { it.copy(eqPostGainDb = db) } },
        )
        Text(
            text = stringResource(R.string.eq_post_gain_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        OutlinedButton(
            onClick = {
                store.update {
                    it.copy(eqBandGainsDb = List(EqualizerSettings.BAND_COUNT) { 0f }, eqPostGainDb = 0f)
                }
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp),
        ) { Text(stringResource(R.string.eq_reset)) }
    }
}

@Composable
private fun PresetMenu(bandGains: List<Float>, enabled: Boolean, onApply: (List<Float>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Shows the preset the bands currently match, or "Custom".
    val current = EqualizerPresets.firstOrNull { it.second == bandGains }?.first
        ?: stringResource(R.string.eq_preset_custom)
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.eq_preset, current))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EqualizerPresets.forEach { (name, gains) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onApply(gains)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** "1K  [----o----]  +3.0 dB", -15..+15 dB in 0.5 dB steps. */
@Composable
private fun GainRow(label: String, gainDb: Float, enabled: Boolean, onGainChange: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Slider(
            value = gainDb,
            onValueChange = { onGainChange((it * 2).roundToInt() / 2f) },
            valueRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = String.format(Locale.getDefault(), "%+.1f dB", gainDb),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/** 31, 62, 125, 250, 500, 1K, 2K, 4K, 8K, 16K -- the desktop's band labels, in Hz. */
private fun bandLabel(band: Int): String {
    val hz = EqualizerSettings.FREQUENCIES_HZ[band]
    return if (hz >= 1000) "${(hz / 1000).toInt()}K" else hz.toInt().toString()
}
