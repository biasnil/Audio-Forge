package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.DEFAULT_TITLE_PHRASES
import com.biasnil.audioforge.data.TitleCleaner
import com.biasnil.audioforge.data.TitleOrder
import com.biasnil.audioforge.data.TitleRules

/**
 * Settings -> Download from link (GitHub version): smart title detection
 * on or off with one example, which side is the artist, and the phrases
 * taken out of brackets -- the built-in ones (each can be turned off) and
 * your own. Nobody has to write a pattern.
 */
@Composable
fun DownloadSettings() {
    val store = LocalAppContainer.current.store
    val settings by store.settings.collectAsStateWithLifecycle()
    val rules = settings.titleRules
    fun update(change: (TitleRules) -> TitleRules) = store.update { it.copy(titleRules = change(it.titleRules)) }

    SettingAnchor("downloads.smart_titles") {
        SettingsSwitchRow(
            label = stringResource(R.string.downloads_smart_titles),
            checked = rules.enabled,
            onCheckedChange = { on -> update { it.copy(enabled = on) } },
        )
        SettingsNote(stringResource(R.string.downloads_smart_titles_example))
    }

    SettingAnchor("downloads.order") {
        SectionHeader(stringResource(R.string.downloads_title_order))
        TitleOrder.entries.forEach { order ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = rules.order == order, enabled = rules.enabled, role = Role.RadioButton) {
                        update { it.copy(order = order) }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = rules.order == order, onClick = null, enabled = rules.enabled)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        when (order) {
                            TitleOrder.Automatic -> R.string.downloads_order_automatic
                            TitleOrder.ArtistFirst -> R.string.downloads_order_artist_first
                            TitleOrder.TitleFirst -> R.string.downloads_order_title_first
                        }
                    )
                )
            }
        }
    }

    SettingAnchor("downloads.phrases") {
        SectionHeader(stringResource(R.string.downloads_phrases))
        SettingsNote(stringResource(R.string.downloads_phrases_note))
        DEFAULT_TITLE_PHRASES.forEach { phrase ->
            val on = phrase !in rules.disabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = on, enabled = rules.enabled, role = Role.Checkbox) { checked ->
                        update { it.copy(disabled = if (checked) it.disabled - phrase else it.disabled + phrase) }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null, enabled = rules.enabled)
                Spacer(Modifier.width(12.dp))
                Text(phrase)
            }
        }
        rules.custom.forEach { phrase ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = true, onCheckedChange = null, enabled = false)
                Spacer(Modifier.width(12.dp))
                Text(phrase, modifier = Modifier.weight(1f))
                IconButton(onClick = { update { it.copy(custom = it.custom - phrase) } }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.downloads_phrase_remove, phrase))
                }
            }
        }
        AddPhraseRow(rules, enabled = rules.enabled) { phrase -> update { it.copy(custom = it.custom + phrase) } }
        ResetPhrasesButton(rules) { update { it.copy(custom = emptyList(), disabled = emptySet()) } }
    }
}

@Composable
private fun AddPhraseRow(rules: TitleRules, enabled: Boolean, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val key = TitleCleaner.phraseKey(text)
    val exists = key.isNotEmpty() && rules.phrases.any { TitleCleaner.phraseKey(it) == key }
    // Very short phrases match whole brackets only, but "(a)" or "(1)" are often meant; say so first.
    val valid = key.length >= 2 && text.trim().length <= MAX_PHRASE_LENGTH && !exists
    fun add() {
        if (!valid) return
        onAdd(text.trim())
        text = ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(MAX_PHRASE_LENGTH) },
        enabled = enabled,
        placeholder = { Text(stringResource(R.string.downloads_phrase_add_hint)) },
        supportingText = if (exists) ({ Text(stringResource(R.string.downloads_phrase_exists)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        trailingIcon = {
            TextButton(onClick = ::add, enabled = enabled && valid) { Text(stringResource(R.string.downloads_phrase_add)) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Back to the built-in phrases; asks first only if it would throw away phrases you added. */
@Composable
private fun ResetPhrasesButton(rules: TitleRules, onReset: () -> Unit) {
    if (rules.custom.isEmpty() && rules.disabled.isEmpty()) return
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { if (rules.custom.isEmpty()) onReset() else confirming = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.downloads_phrases_reset)) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(pluralStringResource(R.plurals.downloads_phrases_reset_confirm, rules.custom.size, rules.custom.size)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onReset() }) { Text(stringResource(R.string.downloads_phrases_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val MAX_PHRASE_LENGTH = 60
