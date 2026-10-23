package com.biasnil.audioforge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String,
    val name: String,
    val trackUris: List<String>,
    /** Your own cover (file name in files/PlaylistCovers); null = a grid of its album covers. */
    val coverFile: String? = null,
)

/** One video assigned to a set of songs (the desktop's WallpaperEntry). */
data class WallpaperEntry(val id: String, val videoUri: String, val trackUris: List<String>)

/** Light/dark: following the phone (System), or fixed. */
enum class Appearance { System, Light, Dark }

/** Effects page -> 3D Surround: headphone virtualization, sized like the space it imitates. */
enum class SurroundPreset { Off, Room, Hall, Stadium }

/** Effects page -> Reverb, separate from (and stacking with) the surround. */
enum class ReverbPreset { Off, SmallRoom, LargeRoom, Hall, Plate }

/** Everything the app remembers between launches (the desktop's AudioForge.ini). */
data class AppSettings(
    val appearance: Appearance = Appearance.System,
    val includePhoneLibrary: Boolean = true,
    /** Tree URIs of added folders (read permission is persisted for each). */
    val folders: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** Your rule-based playlists (the built-in smart lists are SmartPlaylist.BUILT_IN). */
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    /** Names of hidden LibraryTab entries. */
    val hiddenTabs: Set<String> = emptySet(),
    /** 0-200: above 100 is the desktop's volume boost. */
    val volumePercent: Int = 100,
    val replayGainEnabled: Boolean = true,
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = 5,
    val eqEnabled: Boolean = false,
    /** One per band, see EqualizerSettings.FREQUENCIES_HZ. */
    val eqBandGainsDb: List<Float> = List(EQ_BAND_COUNT) { 0f },
    val eqPostGainDb: Float = 0f,
    /** Equalizer -> "A separate EQ for each device": [deviceSounds] is swapped in when the output changes. */
    val deviceSoundEnabled: Boolean = false,
    /** By OutputDevice.key, in the order first seen. */
    val deviceSounds: Map<String, DeviceSound> = emptyMap(),
    // Effects page.
    /** 0 = off, up to ToneParams.MAX_BASS_BOOST_DB. */
    val bassBoostDb: Float = 0f,
    /** More bass and treble the lower the phone's volume (the equal-loudness contours). */
    val loudnessEnabled: Boolean = false,
    /** Turns peaks down smoothly instead of clipping them (matters above 100% volume). */
    val limiterEnabled: Boolean = true,
    /** Karaoke: 0-100, how much of the voice in the middle is taken out; 0 = off. */
    val karaokeVocalRemovalPercent: Int = 0,
    /** Karaoke: the key change in semitones, without changing the speed. */
    val karaokeKeySemitones: Int = 0,
    /** Both ears get the same mix. */
    val monoAudio: Boolean = false,
    /** -100 (left only) .. 0 .. 100 (right only). */
    val balancePercent: Int = 0,
    /** Podcasts: every pause is skipped. */
    val skipSilencePodcasts: Boolean = false,
    /** Songs: only the silence before the first sound and in the last seconds (never mid-song). */
    val skipSilenceSongs: Boolean = false,
    /** Android 14+: send music to a USB DAC untouched (see BitPerfectOutput). */
    val bitPerfectUsb: Boolean = false,
    /** AutoEq headphone correction; null = off. A copy, so it keeps working if the bundled list changes. */
    val headphoneCorrection: HeadphoneProfile? = null,
    val surroundPreset: SurroundPreset = SurroundPreset.Off,
    /** 0-100: how much of the virtual room is mixed in. */
    val surroundAmountPercent: Int = 50,
    /** 0-200: 0 = mono, 100 = unchanged. */
    val stereoWidthPercent: Int = 100,
    val reverbPreset: ReverbPreset = ReverbPreset.Off,
    /** 0-100: dry/wet balance. */
    val reverbMixPercent: Int = 30,
    /** Lets Android's own spatial audio process the music (only shown where supported); off while 3D Surround is on. */
    val androidSpatialAudio: Boolean = true,
    /** Encrypted with a key in the Android Keystore -- see SecretBox. */
    val musixmatchKeyEncrypted: String = "",
    val videoWallpaperEnabled: Boolean = true,
    /** 0-100; 100 = fully visible. */
    val videoWallpaperOpacityPercent: Int = 100,
    /** Used when the playing song has no wallpaper of its own; "" = none. */
    val globalWallpaperUri: String = "",
    val wallpapers: List<WallpaperEntry> = emptyList(),
    /** Phone-library folders whose songs are hidden (Track.folderKey values; subfolders included). */
    val hiddenFolders: Set<String> = emptySet(),
    /** Sleep timer: fade out over the last 30 s (10 s for "end of song"). */
    val sleepFadeOut: Boolean = true,
    /** Tracks of 10+ minutes continue where you stopped. */
    val rememberLongTrackPosition: Boolean = true,
    // Smart Options (need "Analyze Your Songs' Key and BPM" to have run once).
    val analysisCompleted: Boolean = false,
    /** Same-album songs play gapless, crossfades start at the real fade-out, silence is skipped. */
    val smartTransitions: Boolean = false,
    /** The next song's tempo is matched and its beats lined up during a crossfade. */
    val beatMatchedCrossfade: Boolean = false,
    /** Smart shuffle puts songs in compatible keys and similar tempos next to each other. */
    val harmonicShuffle: Boolean = false,
    /** The synced lyric line being sung, in place of the artist on the lock screen and Android Auto. */
    val lyricsOnNowPlaying: Boolean = false,
    /** Settings -> About: look for a newer Android release on GitHub at most once a day. */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckMs: Long = 0,
    /** The newest Android version the last check found ("" = this one is the newest). */
    val latestKnownVersion: String = "",
    /** Now Playing's visualizer look (Settings -> Visualizer). */
    val visualizerStyle: VisualizerStyle = VisualizerStyle.ClassicBars,
    /** GIFs / looping videos the visualizer shows instead of its style (see CustomMediaRepository). */
    val customMedia: List<CustomMediaEntry> = emptyList(),
    /**
     * Folders whose songs are podcasts: added folders (tree URIs) and
     * phone-library folders (Track.folderKey values, subfolders included).
     * Their songs only show in the Podcasts tab and are never analyzed.
     */
    val podcastFolders: Set<String> = emptySet(),
) {
    val smartEnabled: Boolean get() = analysisCompleted && smartTransitions
    val beatMatchEnabled: Boolean get() = analysisCompleted && beatMatchedCrossfade && crossfadeEnabled
    val harmonicEnabled: Boolean get() = analysisCompleted && harmonicShuffle

    companion object {
        const val EQ_BAND_COUNT = 10
        const val MAX_VOLUME_PERCENT = 200
        const val MAX_STEREO_WIDTH_PERCENT = 200
        const val MAX_KEY_SEMITONES = 12
        val CROSSFADE_SECONDS_RANGE = 2..15
    }
}

/**
 * Settings + playlists, kept in memory as a [StateFlow] and saved to
 * files/audioforge.json on every change (Android's built-in org.json, so
 * no extra library).
 */
class AppStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.updateAndGet(transform)
        scope.launch(Dispatchers.IO) {
            // Always writes the latest value, so writes finishing out of
            // order can't leave an older snapshot on disk.
            writeLock.withLock { write(_settings.value) }
        }
    }

    private fun read(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            val root = JSONObject(file.readText())
            AppSettings(
                // Older versions only had a dark-theme switch.
                appearance = Appearance.entries.firstOrNull { it.name == root.optString("appearance") }
                    ?: if (root.has("darkTheme")) {
                        if (root.optBoolean("darkTheme", true)) Appearance.Dark else Appearance.Light
                    } else Appearance.System,
                includePhoneLibrary = root.optBoolean("includePhoneLibrary", true),
                folders = root.optJSONArray("folders").toStringList(),
                playlists = root.optJSONArray("playlists").toObjects().map { obj ->
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.optString("name"),
                        trackUris = obj.optJSONArray("tracks").toStringList(),
                        coverFile = obj.optString("coverFile").ifEmpty { null },
                    )
                },
                smartPlaylists = root.optJSONArray("smartPlaylists").toObjects().map(::readSmartPlaylist),
                // "Tracks" is now Songs, which can't be hidden.
                hiddenTabs = root.optJSONArray("hiddenTabs").toStringList().toSet() - "Tracks",
                sleepFadeOut = root.optBoolean("sleepFadeOut", true),
                rememberLongTrackPosition = root.optBoolean("rememberLongTrackPosition", true),
                analysisCompleted = root.optBoolean("analysisCompleted", false),
                smartTransitions = root.optBoolean("smartTransitions", false),
                beatMatchedCrossfade = root.optBoolean("beatMatchedCrossfade", false),
                harmonicShuffle = root.optBoolean("harmonicShuffle", false),
                lyricsOnNowPlaying = root.optBoolean("lyricsOnNowPlaying", false),
                autoUpdateCheck = root.optBoolean("autoUpdateCheck", true),
                lastUpdateCheckMs = root.optLong("lastUpdateCheckMs", 0),
                latestKnownVersion = root.optString("latestKnownVersion", ""),
                visualizerStyle = VisualizerStyle.entries.firstOrNull { it.name == root.optString("visualizerStyle") }
                    ?: VisualizerStyle.ClassicBars,
                customMedia = root.optJSONArray("customMedia").toObjects().map { obj ->
                    CustomMediaEntry(
                        id = obj.getString("id"),
                        uri = obj.optString("uri"),
                        isVideo = obj.optBoolean("video", false),
                        round = obj.optBoolean("round", true),
                        spin = obj.optBoolean("spin", true),
                        trackUris = obj.optJSONArray("tracks").toStringList(),
                        global = obj.optBoolean("global", false),
                    )
                },
                podcastFolders = root.optJSONArray("podcastFolders").toStringList().toSet(),
                volumePercent = root.optInt("volumePercent", 100).coerceIn(0, AppSettings.MAX_VOLUME_PERCENT),
                replayGainEnabled = root.optBoolean("replayGainEnabled", true),
                crossfadeEnabled = root.optBoolean("crossfadeEnabled", false),
                crossfadeSeconds = root.optInt("crossfadeSeconds", 5).coerceIn(AppSettings.CROSSFADE_SECONDS_RANGE),
                eqEnabled = root.optBoolean("eqEnabled", false),
                eqBandGainsDb = root.optJSONArray("eqBandGainsDb").toFloatList(AppSettings.EQ_BAND_COUNT),
                eqPostGainDb = root.optDouble("eqPostGainDb", 0.0).toFloat(),
                headphoneCorrection = root.optJSONObject("headphoneCorrection")?.let(::readHeadphoneProfile),
                bassBoostDb = root.optDouble("bassBoostDb", 0.0).toFloat().coerceIn(0f, 12f),
                loudnessEnabled = root.optBoolean("loudnessEnabled", false),
                limiterEnabled = root.optBoolean("limiterEnabled", true),
                karaokeVocalRemovalPercent = root.optInt("karaokeVocalRemovalPercent", 0).coerceIn(0, 100),
                monoAudio = root.optBoolean("monoAudio", false),
                balancePercent = root.optInt("balancePercent", 0).coerceIn(-100, 100),
                skipSilencePodcasts = root.optBoolean("skipSilencePodcasts", false),
                skipSilenceSongs = root.optBoolean("skipSilenceSongs", false),
                bitPerfectUsb = root.optBoolean("bitPerfectUsb", false),
                karaokeKeySemitones = root.optInt("karaokeKeySemitones", 0)
                    .coerceIn(-AppSettings.MAX_KEY_SEMITONES, AppSettings.MAX_KEY_SEMITONES),
                deviceSoundEnabled = root.optBoolean("deviceSoundEnabled", false),
                deviceSounds = root.optJSONArray("deviceSounds").toObjects().mapNotNull { obj ->
                    val kind = OutputKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: return@mapNotNull null
                    DeviceSound(
                        device = OutputDevice(kind, obj.optString("productName")),
                        eqEnabled = obj.optBoolean("eqEnabled", false),
                        eqBandGainsDb = obj.optJSONArray("eqBandGainsDb").toFloatList(AppSettings.EQ_BAND_COUNT),
                        eqPostGainDb = obj.optDouble("eqPostGainDb", 0.0).toFloat(),
                        headphoneCorrection = obj.optJSONObject("headphoneCorrection")?.let(::readHeadphoneProfile),
                    )
                }.associateBy { it.device.key },
                surroundPreset = SurroundPreset.entries.firstOrNull { it.name == root.optString("surroundPreset") }
                    ?: SurroundPreset.Off,
                surroundAmountPercent = root.optInt("surroundAmountPercent", 50).coerceIn(0, 100),
                stereoWidthPercent = root.optInt("stereoWidthPercent", 100).coerceIn(0, AppSettings.MAX_STEREO_WIDTH_PERCENT),
                reverbPreset = ReverbPreset.entries.firstOrNull { it.name == root.optString("reverbPreset") }
                    ?: ReverbPreset.Off,
                reverbMixPercent = root.optInt("reverbMixPercent", 30).coerceIn(0, 100),
                androidSpatialAudio = root.optBoolean("androidSpatialAudio", true),
                musixmatchKeyEncrypted = root.optString("musixmatchKeyEncrypted", ""),
                videoWallpaperEnabled = root.optBoolean("videoWallpaperEnabled", true),
                videoWallpaperOpacityPercent = root.optInt("videoWallpaperOpacityPercent", 100).coerceIn(0, 100),
                globalWallpaperUri = root.optString("globalWallpaperUri", ""),
                hiddenFolders = root.optJSONArray("hiddenFolders").toStringList().toSet(),
                wallpapers = root.optJSONArray("wallpapers").toObjects().map { obj ->
                    WallpaperEntry(
                        id = obj.getString("id"),
                        videoUri = obj.optString("videoUri"),
                        trackUris = obj.optJSONArray("tracks").toStringList(),
                    )
                },
            )
        } catch (e: Exception) {
            // A corrupt file shouldn't stop the app from starting.
            Log.w(TAG, "Couldn't read $FILE_NAME, starting with defaults", e)
            AppSettings()
        }
    }

    private fun write(settings: AppSettings) {
        val root = JSONObject()
            .put("version", 1)
            .put("appearance", settings.appearance.name)
            .put("sleepFadeOut", settings.sleepFadeOut)
            .put("rememberLongTrackPosition", settings.rememberLongTrackPosition)
            .put("analysisCompleted", settings.analysisCompleted)
            .put("smartTransitions", settings.smartTransitions)
            .put("beatMatchedCrossfade", settings.beatMatchedCrossfade)
            .put("harmonicShuffle", settings.harmonicShuffle)
            .put("lyricsOnNowPlaying", settings.lyricsOnNowPlaying)
            .put("autoUpdateCheck", settings.autoUpdateCheck)
            .put("lastUpdateCheckMs", settings.lastUpdateCheckMs)
            .put("latestKnownVersion", settings.latestKnownVersion)
            .put("visualizerStyle", settings.visualizerStyle.name)
            .put("customMedia", JSONArray(settings.customMedia.map { entry ->
                JSONObject()
                    .put("id", entry.id)
                    .put("uri", entry.uri)
                    .put("video", entry.isVideo)
                    .put("round", entry.round)
                    .put("spin", entry.spin)
                    .put("tracks", JSONArray(entry.trackUris))
                    .put("global", entry.global)
            }))
            .put("podcastFolders", JSONArray(settings.podcastFolders.toList()))
            .put("includePhoneLibrary", settings.includePhoneLibrary)
            .put("volumePercent", settings.volumePercent)
            .put("folders", JSONArray(settings.folders))
            .put("hiddenTabs", JSONArray(settings.hiddenTabs.toList()))
            .put("replayGainEnabled", settings.replayGainEnabled)
            .put("crossfadeEnabled", settings.crossfadeEnabled)
            .put("crossfadeSeconds", settings.crossfadeSeconds)
            .put("eqEnabled", settings.eqEnabled)
            .put("eqBandGainsDb", JSONArray(settings.eqBandGainsDb.map { it.toDouble() }))
            .put("eqPostGainDb", settings.eqPostGainDb.toDouble())
            .apply { settings.headphoneCorrection?.let { put("headphoneCorrection", writeHeadphoneProfile(it)) } }
            .put("bassBoostDb", settings.bassBoostDb.toDouble())
            .put("loudnessEnabled", settings.loudnessEnabled)
            .put("limiterEnabled", settings.limiterEnabled)
            .put("karaokeVocalRemovalPercent", settings.karaokeVocalRemovalPercent)
            .put("karaokeKeySemitones", settings.karaokeKeySemitones)
            .put("monoAudio", settings.monoAudio)
            .put("balancePercent", settings.balancePercent)
            .put("skipSilencePodcasts", settings.skipSilencePodcasts)
            .put("skipSilenceSongs", settings.skipSilenceSongs)
            .put("bitPerfectUsb", settings.bitPerfectUsb)
            .put("deviceSoundEnabled", settings.deviceSoundEnabled)
            .put("deviceSounds", JSONArray(settings.deviceSounds.values.map { sound ->
                JSONObject()
                    .put("kind", sound.device.kind.name)
                    .put("productName", sound.device.productName)
                    .put("eqEnabled", sound.eqEnabled)
                    .put("eqBandGainsDb", JSONArray(sound.eqBandGainsDb.map { it.toDouble() }))
                    .put("eqPostGainDb", sound.eqPostGainDb.toDouble())
                    .apply { sound.headphoneCorrection?.let { put("headphoneCorrection", writeHeadphoneProfile(it)) } }
            }))
            .put("surroundPreset", settings.surroundPreset.name)
            .put("surroundAmountPercent", settings.surroundAmountPercent)
            .put("stereoWidthPercent", settings.stereoWidthPercent)
            .put("reverbPreset", settings.reverbPreset.name)
            .put("reverbMixPercent", settings.reverbMixPercent)
            .put("androidSpatialAudio", settings.androidSpatialAudio)
            .put("musixmatchKeyEncrypted", settings.musixmatchKeyEncrypted)
            .put("videoWallpaperEnabled", settings.videoWallpaperEnabled)
            .put("videoWallpaperOpacityPercent", settings.videoWallpaperOpacityPercent)
            .put("globalWallpaperUri", settings.globalWallpaperUri)
            .put("hiddenFolders", JSONArray(settings.hiddenFolders.toList()))
            .put("wallpapers", JSONArray(settings.wallpapers.map { entry ->
                JSONObject()
                    .put("id", entry.id)
                    .put("videoUri", entry.videoUri)
                    .put("tracks", JSONArray(entry.trackUris))
            }))
            .put("playlists", JSONArray(settings.playlists.map { playlist ->
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("tracks", JSONArray(playlist.trackUris))
                    .put("coverFile", playlist.coverFile.orEmpty())
            }))
            .put("smartPlaylists", JSONArray(settings.smartPlaylists.map(::writeSmartPlaylist)))
        try {
            // Write-then-rename so a crash mid-write never leaves a truncated file.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                Log.w(TAG, "Couldn't replace $FILE_NAME")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $FILE_NAME", e)
        }
    }

    private companion object {
        const val TAG = "AppStore"
        const val FILE_NAME = "audioforge.json"
    }
}

private fun readSmartPlaylist(obj: JSONObject) = SmartPlaylist(
    id = obj.getString("id"),
    name = obj.optString("name"),
    rules = obj.optJSONArray("rules").toObjects().map { rule ->
        SmartRule(
            field = SmartField.entries.firstOrNull { it.name == rule.optString("field") } ?: SmartField.Genre,
            op = SmartOperator.entries.firstOrNull { it.name == rule.optString("op") } ?: SmartOperator.Contains,
            value = rule.optString("value"),
        )
    },
    matchAll = obj.optBoolean("matchAll", true),
    limit = if (obj.has("limit")) obj.optInt("limit").takeIf { it > 0 } else null,
    sort = SmartSort.entries.firstOrNull { it.name == obj.optString("sort") } ?: SmartSort.Title,
)

private fun writeSmartPlaylist(playlist: SmartPlaylist): JSONObject = JSONObject()
    .put("id", playlist.id)
    .put("name", playlist.name)
    .put("matchAll", playlist.matchAll)
    .put("limit", playlist.limit ?: 0)
    .put("sort", playlist.sort.name)
    .put("rules", JSONArray(playlist.rules.map { rule ->
        JSONObject().put("field", rule.field.name).put("op", rule.op.name).put("value", rule.value)
    }))

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

private fun JSONArray?.toObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Exactly [count] values, 0 dB for any missing ones. */
private fun JSONArray?.toFloatList(count: Int): List<Float> =
    List(count) { index -> this?.optDouble(index, 0.0)?.toFloat()?.takeUnless { it.isNaN() } ?: 0f }

private fun readHeadphoneProfile(obj: JSONObject): HeadphoneProfile? =
    HeadphoneProfile(
        name = obj.optString("name"),
        source = obj.optString("source"),
        kind = obj.optString("kind"),
        preampDb = obj.optDouble("preampDb", 0.0).takeIf { it.isFinite() } ?: 0.0,
        filters = decodeFilters(obj.optString("filters")),
    ).takeIf { it.name.isNotEmpty() && it.filters.isNotEmpty() }

private fun writeHeadphoneProfile(profile: HeadphoneProfile): JSONObject =
    JSONObject()
        .put("name", profile.name)
        .put("source", profile.source)
        .put("kind", profile.kind)
        .put("preampDb", profile.preampDb)
        .put("filters", encodeFilters(profile.filters))
