package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.OutputDevice
import com.biasnil.audioforge.data.OutputKind

/**
 * Equalizer tab -> "A separate EQ for each device": the switch, where
 * music is playing now, and the devices remembered so far (each can be
 * forgotten, except the one playing -- it would be remembered again at once).
 */
@Composable
fun DeviceSoundSection() {
    val container = LocalAppContainer.current
    val store = container.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val current by container.outputDevice.device.collectAsStateWithLifecycle()
    val on = settings.deviceSoundEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(stringResource(R.string.device_sound), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { value -> store.update { it.copy(deviceSoundEnabled = value) } })
    }
    Text(
        text = stringResource(R.string.device_sound_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        text = stringResource(R.string.device_sound_now, outputDeviceName(current)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (on) {
        settings.deviceSounds.values.filter { it.device != current }.forEach { sound ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(outputDeviceName(sound.device))
                    Text(
                        text = if (sound.eqEnabled) stringResource(R.string.device_sound_eq_on) else stringResource(R.string.device_sound_eq_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    store.update { it.copy(deviceSounds = it.deviceSounds - sound.device.key) }
                }) { Text(stringResource(R.string.device_sound_forget)) }
            }
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

/** "Phone speaker", "Wired headphones", or the device's own name ("Galaxy Buds2 Pro", a car's name). */
@Composable
fun outputDeviceName(device: OutputDevice): String = when (device.kind) {
    OutputKind.Speaker -> stringResource(R.string.device_speaker)
    OutputKind.Wired -> stringResource(R.string.device_wired)
    OutputKind.Bluetooth -> device.productName.ifEmpty { stringResource(R.string.device_bluetooth) }
    OutputKind.Usb -> device.productName.ifEmpty { stringResource(R.string.device_usb) }
    OutputKind.Other -> device.productName.ifEmpty { stringResource(R.string.device_other) }
}
