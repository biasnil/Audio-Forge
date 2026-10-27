package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R

/**
 * Settings -> Library: "Leave out very short and very long files" (on by
 * default), and, when it left any out, a row to see them and add them back.
 */
@Composable
fun LengthLimitRows(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val leftOut by container.library.leftOutByLength.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        label = stringResource(R.string.length_skip),
        info = stringResource(R.string.length_skip_explanation),
        checked = settings.skipShortAndLong,
        onCheckedChange = container.library::setSkipShortAndLong,
    )
    if (settings.skipShortAndLong && leftOut.isNotEmpty()) {
        SettingsLinkRow(pluralStringResource(R.plurals.length_left_out, leftOut.size, leftOut.size)) {
            onOpenPage(Page.LeftOutByLength)
        }
    }
}

/** The songs the length limits left out; Add puts one in the library for good. */
@Composable
fun LeftOutByLengthPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val leftOut by container.library.leftOutByLength.collectAsStateWithLifecycle()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    // Nothing left to show (all added, or the setting turned off): back to Settings.
    LaunchedEffect(leftOut.isEmpty(), settings.skipShortAndLong) {
        if (leftOut.isEmpty() || !settings.skipShortAndLong) onBack()
    }
    Scaffold(topBar = { DetailTopBar(stringResource(R.string.length_page_title), onBack) }) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding).fillMaxSize()) {
            item(key = "note") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SettingsNote(stringResource(R.string.length_page_note))
                    Button(
                        onClick = { container.library.addByHand(leftOut.map { it.uri }) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(pluralStringResource(R.plurals.length_add_all, leftOut.size, leftOut.size)) }
                }
            }
            items(leftOut, key = { it.uri }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = false,
                    onClick = {},
                    trailing = {
                        TextButton(onClick = { container.library.addByHand(listOf(track.uri)) }) {
                            Text(stringResource(R.string.length_add))
                        }
                    },
                )
            }
        }
    }
}
