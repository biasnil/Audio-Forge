package com.biasnil.audioforge.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// Internet radio: stations from Radio Browser (radio-browser.info), a free,
// community-run directory, ported from the owner's desktop app World_Radio
// (core/radio_browser.py). Plain Kotlin, tested on the JVM
// (RadioBrowserTest); RadioRepository does the network and files.

/** An internet radio station. [uuid] is Radio Browser's stable id. */
data class RadioStation(
    val uuid: String,
    val name: String,
    /** url_resolved (playlists already resolved), else url. */
    val streamUrl: String,
    val homepage: String = "",
    val favicon: String? = null,
    val tags: List<String> = emptyList(),
    /** ISO 3166-1 alpha-2, upper case; "" if unknown. */
    val countryCode: String = "",
    val codec: String = "",
    val bitrateKbps: Int = 0,
    /** An HLS (.m3u8) stream. */
    val hls: Boolean = false,
)

/** A country in the station directory: [code] is ISO 3166-1 alpha-2. */
data class RadioCountry(val code: String, val stations: Int)

/** Known mirrors, tried when the DNS lookup of all.api.radio-browser.info gives nothing (the desktop's list). */
val RADIO_BROWSER_MIRRORS = listOf(
    "de1.api.radio-browser.info",
    "de2.api.radio-browser.info",
    "at1.api.radio-browser.info",
    "nl1.api.radio-browser.info",
)

/** Page sizes: a quick first page to show, then bigger ones (as the desktop pages). */
const val RADIO_FIRST_PAGE = 500
const val RADIO_NEXT_PAGE = 1000

/** How many stations Recently played keeps (the desktop's MAX_RECENT). */
const val MAX_RECENT_STATIONS = 20

/**
 * A page of /json/stations/search: by name, ISO country code and/or tag
 * (all optional), ordered by name so pages don't overlap, broken stations
 * hidden.
 */
fun stationSearchPath(name: String = "", countryCode: String = "", tag: String = "", offset: Int = 0, limit: Int = RADIO_FIRST_PAGE): String {
    val params = buildList {
        if (name.isNotBlank()) add("name" to name.trim())
        if (countryCode.isNotBlank()) add("countrycode" to countryCode.trim())
        if (tag.isNotBlank()) add("tag" to tag.trim().lowercase(Locale.ROOT))
        add("hidebroken" to "true")
        add("order" to "name")
        add("offset" to offset.toString())
        add("limit" to limit.toString())
    }
    return "/json/stations/search?" + params.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
}

fun topStationsPath(limit: Int = 100): String = "/json/stations/topclick/$limit?hidebroken=true"

const val COUNTRY_CODES_PATH = "/json/countrycodes?hidebroken=true"

/** Tells Radio Browser a station started playing (their click count; once a day per station and IP counts). */
fun clickPath(uuid: String): String = "/json/url/" + URLEncoder.encode(uuid, "UTF-8")

/** A vote for a station (once per station and IP every 10 minutes). */
fun votePath(uuid: String): String = "/json/vote/" + URLEncoder.encode(uuid, "UTF-8")

/** Stations from a Radio Browser response; ones without a stream are skipped, each uuid once. */
fun parseStations(json: String): List<RadioStation> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val url = obj.optString("url_resolved").trim().ifEmpty { obj.optString("url").trim() }
        val uuid = obj.optString("stationuuid")
        if (!url.startsWith("http") || uuid.isEmpty()) return@mapNotNull null
        RadioStation(
            uuid = uuid,
            name = obj.optString("name").trim().ifEmpty { url },
            streamUrl = url,
            homepage = obj.optString("homepage").trim(),
            favicon = obj.optString("favicon").trim().takeIf { it.startsWith("http") },
            tags = obj.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            countryCode = obj.optString("countrycode").trim().uppercase(Locale.ROOT),
            codec = obj.optString("codec").trim(),
            bitrateKbps = obj.optInt("bitrate"),
            hls = obj.optInt("hls") == 1,
        )
    }.distinctBy { it.uuid }
}

/**
 * Countries with stations, from /json/countrycodes (whose "name" is the ISO
 * code). Filtering by code, not the free-text country name, is what the
 * desktop learned: "USA" vs "United States" splits a country's stations.
 */
fun parseCountryCodes(json: String): List<RadioCountry> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val code = obj.optString("name").trim().uppercase(Locale.ROOT)
        if (code.length != 2) return@mapNotNull null
        RadioCountry(code, obj.optInt("stationcount"))
    }
}

/** "Malaysia", "Malaisie", "马来西亚": the country's name in [locale]; the code itself if it isn't an ISO country. */
fun countryName(code: String, locale: Locale): String {
    if (code !in ISO_COUNTRIES) return code
    return runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: code
}

private val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toHashSet()

/** Recently played: [station] first, no repeats, at most [max]. */
fun addRecentStation(recent: List<RadioStation>, station: RadioStation, max: Int = MAX_RECENT_STATIONS): List<RadioStation> =
    (listOf(station) + recent.filterNot { it.uuid == station.uuid }).take(max)

/** "Pop, Rock · MP3 128 kbps": the station's details under its name. */
fun stationDetails(station: RadioStation, country: String?): String {
    val format = listOf(station.codec.uppercase(Locale.ROOT).takeIf { it.isNotEmpty() && it != "UNKNOWN" }, station.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" })
        .filterNotNull().joinToString(" ")
    val tags = station.tags.take(3).joinToString(", ") { tag -> tag.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    return listOf(country.orEmpty(), tags, format).filter { it.isNotEmpty() }.joinToString("  •  ")
}

// --- Saving favorites and recent ----------------------------------------------

fun RadioStation.toJson(): JSONObject = JSONObject().apply {
    put("uuid", uuid)
    put("name", name)
    put("url", streamUrl)
    if (homepage.isNotEmpty()) put("homepage", homepage)
    favicon?.let { put("favicon", it) }
    if (tags.isNotEmpty()) put("tags", tags.joinToString(","))
    if (countryCode.isNotEmpty()) put("country", countryCode)
    if (codec.isNotEmpty()) put("codec", codec)
    if (bitrateKbps > 0) put("bitrate", bitrateKbps)
    if (hls) put("hls", true)
}

fun radioStationFromJson(obj: JSONObject): RadioStation? {
    val uuid = obj.optString("uuid").ifEmpty { return null }
    val url = obj.optString("url").ifEmpty { return null }
    return RadioStation(
        uuid = uuid,
        name = obj.optString("name"),
        streamUrl = url,
        homepage = obj.optString("homepage"),
        favicon = obj.optString("favicon").ifEmpty { null },
        tags = obj.optString("tags").split(',').filter { it.isNotEmpty() },
        countryCode = obj.optString("country"),
        codec = obj.optString("codec"),
        bitrateKbps = obj.optInt("bitrate"),
        hls = obj.optBoolean("hls"),
    )
}

/** files/radio.json: {"favorites": [...], "recent": [...]}. */
fun radioListsToJson(favorites: List<RadioStation>, recent: List<RadioStation>): String = JSONObject().apply {
    put("favorites", JSONArray(favorites.map { it.toJson() }))
    put("recent", JSONArray(recent.map { it.toJson() }))
}.toString()

fun radioListsFromJson(json: String): Pair<List<RadioStation>, List<RadioStation>> {
    val root = JSONObject(json)
    fun list(key: String): List<RadioStation> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::radioStationFromJson) }
    }
    return list("favorites") to list("recent")
}
