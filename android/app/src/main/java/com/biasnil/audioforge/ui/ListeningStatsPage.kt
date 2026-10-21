package com.biasnil.audioforge.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.DetectedGenre
import com.biasnil.audioforge.data.ListeningStats
import com.biasnil.audioforge.data.PlayEntry
import com.biasnil.audioforge.data.RankedItem
import com.biasnil.audioforge.data.StatsPeriod
import com.biasnil.audioforge.data.listenedAmount
import com.biasnil.audioforge.data.listeningStats
import com.biasnil.audioforge.data.monthOf
import com.biasnil.audioforge.data.shifted
import com.biasnil.audioforge.data.yearOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which kind of period the page shows. */
private enum class PeriodKind { Month, Year, AllTime }

/**
 * Settings -> Listening stats: top songs, artists, albums and genres, and
 * hours listened, per month, per year or for all time, from the listening log
 * (data/PlayLog.kt). Podcasts are counted separately. A year can be shared as
 * an image ("AudioForge Year in Review"). Everything stays on the phone.
 */
@Composable
fun ListeningStatsPage(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val entries by container.playLog.entries.collectAsStateWithLifecycle()
    val genres by container.genres.genres.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }

    var kind by rememberSaveable { mutableStateOf(PeriodKind.Month) }
    // Months / years back from now (0 = this one).
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val period = when (kind) {
        PeriodKind.Month -> monthOf(now, zone).shifted(-offset)
        PeriodKind.Year -> yearOf(now, zone).shifted(-offset)
        PeriodKind.AllTime -> StatsPeriod.AllTime
    }
    // No going back past the first logged play.
    val firstPlayMs = entries.firstOrNull()?.startedAtMs
    val canGoBack = firstPlayMs != null && when (period) {
        is StatsPeriod.Month -> YearMonth.of(period.year, period.month) > YearMonth.from(java.time.Instant.ofEpochMilli(firstPlayMs).atZone(zone))
        is StatsPeriod.Year -> period.year > yearOf(firstPlayMs, zone).year
        StatsPeriod.AllTime -> false
    }

    /** Detected genre (Settings -> Detect Genres), else the song's genre tag. */
    fun genreOf(play: PlayEntry): String? {
        val track = container.library.trackFor(play.uri) ?: return null
        val detected = container.genres.genreFor(track)?.takeIf { it.status == DetectedGenre.Status.OK }?.broad
        return detected ?: track.genre.ifBlank { null }
    }

    val stats by produceState(ListeningStats(), entries, genres, period) {
        value = withContext(Dispatchers.Default) { listeningStats(entries, period, zone, ::genreOf) }
    }
    val periodTitle = periodTitle(period, locale)

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.listening_stats), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val options = PeriodKind.entries
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = kind == option,
                        onClick = {
                            kind = option
                            offset = 0
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(
                            stringResource(
                                when (option) {
                                    PeriodKind.Month -> R.string.stats_month
                                    PeriodKind.Year -> R.string.stats_year
                                    PeriodKind.AllTime -> R.string.stats_all_time
                                }
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }

            // ‹ October 2026 ›
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (period != StatsPeriod.AllTime) {
                    IconButton(onClick = { offset++ }, enabled = canGoBack) {
                        Icon(AppIcons.ChevronLeft, contentDescription = stringResource(R.string.stats_earlier))
                    }
                }
                Text(
                    text = periodTitle,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                if (period != StatsPeriod.AllTime) {
                    IconButton(onClick = { offset-- }, enabled = offset > 0) {
                        Icon(AppIcons.ChevronRight, contentDescription = stringResource(R.string.stats_later))
                    }
                }
            }

            if (stats.isEmpty) {
                CenteredText(
                    text = stringResource(R.string.stats_empty),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                )
            } else {
                Summary(stats, locale)
                TopList(stringResource(R.string.stats_top_songs), stats.songs)
                TopList(stringResource(R.string.stats_top_artists), stats.artists)
                TopList(stringResource(R.string.stats_top_albums), stats.albums)
                TopList(stringResource(R.string.stats_top_genres), stats.genres)
                if (stats.genres.isEmpty() && stats.plays > 0) Hint(stringResource(R.string.stats_genres_hint))

                if (period is StatsPeriod.Year && stats.plays > 0) {
                    val title = stringResource(R.string.year_in_review)
                    FilledTonalButton(
                        onClick = { scope.launch { shareYearInReview(context, period.year, stats, title) } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.year_in_review_share))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Hint(stringResource(R.string.stats_since))
        }
    }
}

/** "12.5" + "hours of music", or "45" + "minutes of music" under an hour. */
internal fun amountParts(context: Context, ms: Long, locale: Locale): Pair<String, String> {
    val amount = listenedAmount(ms)
    return if (amount.inHours) String.format(locale, "%.1f", amount.value) to context.getString(R.string.stats_hours)
    else String.format(locale, "%.0f", amount.value) to context.getString(R.string.stats_minutes)
}

/** "1.5 h" / "45 min". */
private fun shortAmount(context: Context, ms: Long, locale: Locale): String {
    val amount = listenedAmount(ms)
    return if (amount.inHours) context.getString(R.string.stats_short_hours, String.format(locale, "%.1f", amount.value))
    else context.getString(R.string.stats_short_minutes, String.format(locale, "%.0f", amount.value))
}

/** Time listened and plays; podcasts on their own line. */
@Composable
private fun Summary(stats: ListeningStats, locale: Locale) {
    val context = LocalContext.current
    val (amount, unit) = amountParts(context, stats.listenedMs, locale)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(amount, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(pluralStringResource(R.plurals.stats_plays, stats.plays, stats.plays), fontWeight = FontWeight.SemiBold)
            if (stats.podcastPlays > 0) {
                Text(
                    text = stringResource(
                        R.string.stats_podcasts,
                        shortAmount(context, stats.podcastMs, locale),
                        pluralStringResource(R.plurals.podcast_episode_count, stats.podcastPlays, stats.podcastPlays),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun TopList(title: String, items: List<RankedItem>) {
    if (items.isEmpty()) return
    SectionHeader(title)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.detail.isNotEmpty()) {
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    pluralStringResource(R.plurals.stats_plays, item.plays, item.plays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < items.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun periodTitle(period: StatsPeriod, locale: Locale): String = when (period) {
    // "October 2026", "octobre 2026", "2026年10月": the locale's own order.
    is StatsPeriod.Month -> YearMonth.of(period.year, period.month)
        .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "LLLLyyyy"), locale))
        .replaceFirstChar { it.titlecase(locale) }
    is StatsPeriod.Year -> period.year.toString()
    StatsPeriod.AllTime -> stringResource(R.string.stats_all_time)
}
