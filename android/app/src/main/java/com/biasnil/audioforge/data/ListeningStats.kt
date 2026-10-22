package com.biasnil.audioforge.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// Settings -> Listening stats: the listening log (PlayLog.kt) added up per
// month, per year or for all time. Plain Kotlin, tested on the JVM
// (ListeningStatsTest).

/** The stretch of time the stats cover. */
sealed interface StatsPeriod {
    data class Month(val year: Int, val month: Int) : StatsPeriod
    data class Year(val year: Int) : StatsPeriod
    data object AllTime : StatsPeriod
}

/** Whether a play started at [timeMs] (in [zone]'s calendar) falls in this period. */
fun StatsPeriod.contains(timeMs: Long, zone: ZoneId): Boolean {
    if (this == StatsPeriod.AllTime) return true
    val date = Instant.ofEpochMilli(timeMs).atZone(zone)
    return when (this) {
        is StatsPeriod.Month -> date.year == year && date.monthValue == month
        is StatsPeriod.Year -> date.year == year
        StatsPeriod.AllTime -> true
    }
}

/** The period before / after this one (all time has none). */
fun StatsPeriod.shifted(by: Int): StatsPeriod = when (this) {
    is StatsPeriod.Month -> YearMonth.of(year, month).plusMonths(by.toLong()).let { StatsPeriod.Month(it.year, it.monthValue) }
    is StatsPeriod.Year -> StatsPeriod.Year(year + by)
    StatsPeriod.AllTime -> this
}

/** The month / year containing [timeMs]. */
fun monthOf(timeMs: Long, zone: ZoneId): StatsPeriod.Month =
    Instant.ofEpochMilli(timeMs).atZone(zone).let { StatsPeriod.Month(it.year, it.monthValue) }

fun yearOf(timeMs: Long, zone: ZoneId): StatsPeriod.Year = StatsPeriod.Year(Instant.ofEpochMilli(timeMs).atZone(zone).year)

/** A song, artist, album or genre in a top list. [detail] is the artist (songs, albums), else "". */
data class RankedItem(val name: String, val detail: String, val plays: Int, val listenedMs: Long)

data class ListeningStats(
    /** Music only; podcasts are [podcastMs] / [podcastPlays], radio [radioMs] / [radioPlays]. */
    val listenedMs: Long = 0,
    val plays: Int = 0,
    val songs: List<RankedItem> = emptyList(),
    val artists: List<RankedItem> = emptyList(),
    val albums: List<RankedItem> = emptyList(),
    val genres: List<RankedItem> = emptyList(),
    val podcastMs: Long = 0,
    val podcastPlays: Int = 0,
    val radioMs: Long = 0,
    /** Stations played (each start of a station). */
    val radioPlays: Int = 0,
) {
    val isEmpty: Boolean get() = plays == 0 && podcastPlays == 0 && radioPlays == 0
}

/** How many songs, artists... each top list shows. */
const val TOP_COUNT = 10

/**
 * Adds up the plays in [period]. Top lists are ordered by plays, then time
 * listened, then name. Songs are told apart by file, artists, albums and
 * genres by name (ignoring case); plays without an artist / album / genre
 * ([genreOf] returns null) count in the totals but not in that list.
 */
fun listeningStats(
    entries: List<PlayEntry>,
    period: StatsPeriod,
    zone: ZoneId,
    genreOf: (PlayEntry) -> String?,
    top: Int = TOP_COUNT,
): ListeningStats {
    val inPeriod = entries.filter { period.contains(it.startedAtMs, zone) }
    val music = inPeriod.filter { it.kind == PlayKind.Music }
    val podcasts = inPeriod.filter { it.kind == PlayKind.Podcast }
    val radio = inPeriod.filter { it.kind == PlayKind.Radio }
    return ListeningStats(
        listenedMs = music.sumOf { it.listenedMs },
        plays = music.size,
        songs = ranked(music, top, key = { it.uri }, name = { it.title }, detail = { it.artist }),
        artists = ranked(music.filter { it.artist.isNotBlank() }, top, key = { it.artist.trim().lowercase() }, name = { it.artist.trim() }),
        albums = ranked(music.filter { it.album.isNotBlank() }, top, key = { it.album.trim().lowercase() }, name = { it.album.trim() }, detail = { it.artist }),
        genres = rankedGenres(music, top, genreOf),
        podcastMs = podcasts.sumOf { it.listenedMs },
        podcastPlays = podcasts.size,
        radioMs = radio.sumOf { it.listenedMs },
        radioPlays = radio.size,
    )
}

private fun ranked(
    plays: List<PlayEntry>,
    top: Int,
    key: (PlayEntry) -> String,
    name: (PlayEntry) -> String,
    detail: (PlayEntry) -> String = { "" },
): List<RankedItem> =
    plays.groupBy(key).values
        // The most recent play names the group (its latest tags).
        .map { group -> group.maxBy { it.startedAtMs }.let { RankedItem(name(it), detail(it), group.size, group.sumOf { p -> p.listenedMs }) } }
        .sortedWith(RANKING)
        .take(top)

private fun rankedGenres(plays: List<PlayEntry>, top: Int, genreOf: (PlayEntry) -> String?): List<RankedItem> =
    plays.mapNotNull { play -> genreOf(play)?.trim()?.takeIf(String::isNotEmpty)?.let { it to play } }
        .groupBy { it.first.lowercase() }.values
        .map { group -> RankedItem(group.first().first, "", group.size, group.sumOf { it.second.listenedMs }) }
        .sortedWith(RANKING)
        .take(top)

private val RANKING = compareByDescending<RankedItem> { it.plays }
    .thenByDescending { it.listenedMs }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** Listening time as the page shows it: whole minutes under an hour, else hours to one decimal. */
data class ListenedAmount(val value: Double, val inHours: Boolean)

fun listenedAmount(ms: Long): ListenedAmount =
    if (ms < HOUR_MS) ListenedAmount((ms / 60_000).toDouble(), inHours = false)
    else ListenedAmount(ms / HOUR_MS.toDouble(), inHours = true)

private const val HOUR_MS = 3_600_000L
