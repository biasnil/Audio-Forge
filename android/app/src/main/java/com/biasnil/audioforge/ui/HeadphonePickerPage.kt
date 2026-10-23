package com.biasnil.audioforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.HeadphoneProfile
import com.biasnil.audioforge.data.searchHeadphones

/**
 * Effects -> Headphone correction -> Choose: search the bundled AutoEq
 * profiles by model name and tap one to use it. A model measured by
 * several sources is listed once per source, the best-known first.
 */
@Composable
fun HeadphonePickerPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.store.settings.collectAsStateWithLifecycle()
    var profiles by remember { mutableStateOf<List<HeadphoneProfile>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { profiles = container.headphoneProfiles.all() }
    val all = profiles
    val results = remember(all, query) { all?.let { searchHeadphones(it, query, MAX_RESULTS) }.orEmpty() }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.headphones_choose), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.headphones_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.isEmpty() -> Text(
                    text = stringResource(R.string.headphones_none_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    // Keyed by position: the list is rebuilt for every search anyway.
                    itemsIndexed(results, key = { index, _ -> index }) { _, profile ->
                        val chosen = profile == settings.headphoneCorrection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    container.store.update { it.copy(headphoneCorrection = profile) }
                                    onBack()
                                }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = headphoneSourceLine(profile),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (chosen) Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (results.size == MAX_RESULTS) {
                        item {
                            Text(
                                text = stringResource(R.string.headphones_more_results),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "oratory1990 · over-ear": who measured it, and how. */
fun headphoneSourceLine(profile: HeadphoneProfile): String = "${profile.source} · ${profile.kind}"

private const val MAX_RESULTS = 200
