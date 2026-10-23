package com.biasnil.audioforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.ColourStyle
import com.biasnil.audioforge.ui.theme.ACCENT_SWATCHES
import com.biasnil.audioforge.ui.theme.contrastRatio

/**
 * Settings -> Appearance, under light/dark: "Pure black" for dark mode, and
 * the colours -- the app's blue, the wallpaper's (Material You), or one of
 * [ACCENT_SWATCHES]. Tapping a swatch also picks "Your colour".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSettings() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = settings.pureBlack, role = Role.Switch) { on -> store.update { it.copy(pureBlack = on) } }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.theme_pure_black), modifier = Modifier.weight(1f))
        Switch(checked = settings.pureBlack, onCheckedChange = null)
    }
    ThemeNote(stringResource(R.string.theme_pure_black_explanation))

    SectionHeader(stringResource(R.string.theme_colours))
    ColourStyle.entries.forEach { style ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = settings.colourStyle == style, role = Role.RadioButton) {
                    store.update { it.copy(colourStyle = style) }
                }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = settings.colourStyle == style, onClick = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (style) {
                            ColourStyle.AudioForge -> R.string.theme_colours_app
                            ColourStyle.MaterialYou -> R.string.theme_colours_material_you
                            ColourStyle.Custom -> R.string.theme_colours_custom
                        }
                    )
                )
                if (style == ColourStyle.MaterialYou) {
                    Text(
                        text = stringResource(R.string.theme_colours_material_you_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ACCENT_SWATCHES.forEachIndexed { index, argb ->
            val selected = settings.colourStyle == ColourStyle.Custom && settings.accentColor == argb
            val label = stringResource(R.string.theme_colour_number, index + 1)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .selectable(selected = selected, role = Role.RadioButton) {
                        store.update { it.copy(colourStyle = ColourStyle.Custom, accentColor = argb) }
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    // White or black tick, whichever reads better on this colour.
                    val tick = if (contrastRatio(argb, WHITE) >= contrastRatio(argb, BLACK)) Color.White else Color.Black
                    Icon(AppIcons.Check, contentDescription = null, tint = tick)
                }
            }
        }
    }
}

@Composable
private fun ThemeNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
