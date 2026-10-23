package com.biasnil.audioforge.ui

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.audio.ToneParams
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.ReverbPreset
import com.biasnil.audioforge.data.SurroundPreset
import kotlin.math.roundToInt

/**
 * Equalizer -> Effects: AutoEq headphone correction (chosen on
 * [HeadphonePickerPage]), bass boost, loudness, the limiter, 3D Surround, stereo width, reverb, and (where the
 * phone supports it) a switch for Android's own spatial audio. Our surround
 * and Android's never run together: choosing one turns the other off.
 * Changes apply to the music immediately, including during a crossfade.
 */
@Composable
fun EffectsPage(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spatialOnInPhone = remember { spatialAudioCheck(context) }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.effects), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            SectionHeader(stringResource(R.string.headphones_correction))
            Explanation(stringResource(R.string.headphones_correction_explanation))
            val profile = settings.headphoneCorrection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPage(Page.HeadphonePicker) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(profile?.name ?: stringResource(R.string.headphones_choose))
                    Text(
                        text = if (profile != null) headphoneSourceLine(profile) else stringResource(R.string.mode_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (profile != null) {
                    TextButton(onClick = { store.update { it.copy(headphoneCorrection = null) } }) {
                        Text(stringResource(R.string.headphones_turn_off))
                    }
                } else {
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.tone_section))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tone_bass), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
                Slider(
                    value = settings.bassBoostDb,
                    onValueChange = { db -> store.update { it.copy(bassBoostDb = db.roundToInt().toFloat()) } },
                    valueRange = 0f..ToneParams.MAX_BASS_BOOST_DB,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (settings.bassBoostDb == 0f) stringResource(R.string.mode_off) else "+${settings.bassBoostDb.roundToInt()} dB",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
            SwitchRow(
                label = stringResource(R.string.tone_loudness),
                explanation = stringResource(R.string.tone_loudness_explanation),
                checked = settings.loudnessEnabled,
                onChange = { on -> store.update { it.copy(loudnessEnabled = on) } },
            )
            SwitchRow(
                label = stringResource(R.string.tone_limiter),
                explanation = stringResource(R.string.tone_limiter_explanation),
                checked = settings.limiterEnabled,
                onChange = { on -> store.update { it.copy(limiterEnabled = on) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.effects_surround))
            Explanation(stringResource(R.string.effects_surround_explanation))
            ChoiceChips(
                options = SurroundPreset.entries,
                selected = settings.surroundPreset,
                label = { surroundName(it) },
                onSelect = { preset ->
                    store.update {
                        it.copy(
                            surroundPreset = preset,
                            androidSpatialAudio = it.androidSpatialAudio && preset == SurroundPreset.Off,
                        )
                    }
                },
            )
            PercentRow(
                label = stringResource(R.string.effects_amount),
                percent = settings.surroundAmountPercent,
                max = 100,
                enabled = settings.surroundPreset != SurroundPreset.Off,
                onChange = { value -> store.update { it.copy(surroundAmountPercent = value) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.effects_width))
            Explanation(stringResource(R.string.effects_width_explanation))
            PercentRow(
                label = stringResource(R.string.effects_width_short),
                percent = settings.stereoWidthPercent,
                max = AppSettings.MAX_STEREO_WIDTH_PERCENT,
                enabled = true,
                onChange = { value -> store.update { it.copy(stereoWidthPercent = value) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.effects_reverb))
            ChoiceChips(
                options = ReverbPreset.entries,
                selected = settings.reverbPreset,
                label = { reverbName(it) },
                onSelect = { preset -> store.update { it.copy(reverbPreset = preset) } },
            )
            PercentRow(
                label = stringResource(R.string.effects_mix),
                percent = settings.reverbMixPercent,
                max = 100,
                enabled = settings.reverbPreset != ReverbPreset.Off,
                onChange = { value -> store.update { it.copy(reverbMixPercent = value) } },
            )

            if (spatialOnInPhone != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(stringResource(R.string.effects_android_spatial), Modifier.weight(1f))
                    Switch(
                        checked = settings.androidSpatialAudio && settings.surroundPreset == SurroundPreset.Off,
                        onCheckedChange = { on ->
                            store.update {
                                it.copy(
                                    androidSpatialAudio = on,
                                    surroundPreset = if (on) SurroundPreset.Off else it.surroundPreset,
                                )
                            }
                        },
                    )
                }
                Explanation(stringResource(R.string.effects_android_spatial_explanation))
                // Read each time the page is shown: the phone's own switch may have changed since.
                if (!spatialOnInPhone()) {
                    Explanation(stringResource(R.string.effects_android_spatial_off_in_system))
                }
            }

            OutlinedButton(
                onClick = {
                    store.update {
                        it.copy(
                            bassBoostDb = 0f,
                            loudnessEnabled = false,
                            surroundPreset = SurroundPreset.Off,
                            surroundAmountPercent = 50,
                            stereoWidthPercent = 100,
                            reverbPreset = ReverbPreset.Off,
                            reverbMixPercent = 30,
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp),
            ) { Text(stringResource(R.string.effects_reset)) }
        }
    }
}

/**
 * If this phone can spatialize at all (Android 13+ with a supported audio
 * chain): a check of whether spatial audio is on in the phone's own
 * settings. Null otherwise, and the switch is hidden.
 */
private fun spatialAudioCheck(context: Context): (() -> Boolean)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val spatializer = context.getSystemService(AudioManager::class.java)?.spatializer ?: return null
    if (spatializer.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) return null
    return { spatializer.isEnabled }
}

/** A switch with its label, and a line of explanation under it. */
@Composable
private fun SwitchRow(label: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    Explanation(explanation)
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** One chip per option; chips wrap onto a second line on narrow phones or long translations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** "Amount  [----o----]  50%", in steps of 5%. */
@Composable
private fun PercentRow(label: String, percent: Int, max: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(((it / 5).roundToInt() * 5).coerceIn(0, max)) },
            valueRange = 0f..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

@Composable
private fun surroundName(preset: SurroundPreset): String = when (preset) {
    SurroundPreset.Off -> stringResource(R.string.mode_off)
    SurroundPreset.Room -> stringResource(R.string.effects_room)
    SurroundPreset.Hall -> stringResource(R.string.effects_hall)
    SurroundPreset.Stadium -> stringResource(R.string.effects_stadium)
}

@Composable
private fun reverbName(preset: ReverbPreset): String = when (preset) {
    ReverbPreset.Off -> stringResource(R.string.mode_off)
    ReverbPreset.SmallRoom -> stringResource(R.string.effects_small_room)
    ReverbPreset.LargeRoom -> stringResource(R.string.effects_large_room)
    ReverbPreset.Hall -> stringResource(R.string.effects_hall)
    ReverbPreset.Plate -> stringResource(R.string.effects_plate)
}
