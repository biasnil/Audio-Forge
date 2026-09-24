package com.biasnil.audioforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.biasnil.audioforge.R
import com.biasnil.audioforge.ui.theme.AudioForgeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioForgeApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    // Tracks first, same as the desktop app's startup tab.
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.Tracks) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onToggleTheme) {
                        Text(
                            stringResource(
                                if (darkTheme) R.string.theme_switch_to_light else R.string.theme_switch_to_dark
                            )
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 0.dp,
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.title)) },
                    )
                }
            }

            TabPlaceholder(
                tab = selectedTab,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

/** Stand-in for each tab's content until the stage that builds it. */
@Composable
private fun TabPlaceholder(tab: LibraryTab, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.tab_coming_in_stage, stringResource(tab.title), tab.stage),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AudioForgeAppDarkPreview() {
    AudioForgeTheme(darkTheme = true) {
        AudioForgeApp(darkTheme = true, onToggleTheme = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AudioForgeAppLightPreview() {
    AudioForgeTheme(darkTheme = false) {
        AudioForgeApp(darkTheme = false, onToggleTheme = {})
    }
}
