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

data class Playlist(val id: String, val name: String, val trackUris: List<String>)

/** One video assigned to a set of songs (the desktop's WallpaperEntry). */
data class WallpaperEntry(val id: String, val videoUri: String, val trackUris: List<String>)

/** Everything the app remembers between launches (the desktop's AudioForge.ini). */
data class AppSettings(
    val darkTheme: Boolean = true,
    val includePhoneLibrary: Boolean = true,
    /** Tree URIs of added folders (read permission is persisted for each). */
    val folders: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
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
) {
    companion object {
        const val EQ_BAND_COUNT = 10
        const val MAX_VOLUME_PERCENT = 200
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
                darkTheme = root.optBoolean("darkTheme", true),
                includePhoneLibrary = root.optBoolean("includePhoneLibrary", true),
                folders = root.optJSONArray("folders").toStringList(),
                playlists = root.optJSONArray("playlists").toObjects().map { obj ->
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.optString("name"),
                        trackUris = obj.optJSONArray("tracks").toStringList(),
                    )
                },
                hiddenTabs = root.optJSONArray("hiddenTabs").toStringList().toSet(),
                volumePercent = root.optInt("volumePercent", 100).coerceIn(0, AppSettings.MAX_VOLUME_PERCENT),
                replayGainEnabled = root.optBoolean("replayGainEnabled", true),
                crossfadeEnabled = root.optBoolean("crossfadeEnabled", false),
                crossfadeSeconds = root.optInt("crossfadeSeconds", 5).coerceIn(AppSettings.CROSSFADE_SECONDS_RANGE),
                eqEnabled = root.optBoolean("eqEnabled", false),
                eqBandGainsDb = root.optJSONArray("eqBandGainsDb").toFloatList(AppSettings.EQ_BAND_COUNT),
                eqPostGainDb = root.optDouble("eqPostGainDb", 0.0).toFloat(),
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
            .put("darkTheme", settings.darkTheme)
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
            }))
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

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

private fun JSONArray?.toObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Exactly [count] values, 0 dB for any missing ones. */
private fun JSONArray?.toFloatList(count: Int): List<Float> =
    List(count) { index -> this?.optDouble(index, 0.0)?.toFloat()?.takeUnless { it.isNaN() } ?: 0f }
