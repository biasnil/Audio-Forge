package com.biasnil.audioforge.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The phone's own music index (needs READ_MEDIA_AUDIO). Fast: no file is opened. */
class MediaStoreSource(private val resolver: ContentResolver) {

    suspend fun queryMusic(): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.MediaColumns.TITLE,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.GENRE,
            MediaStore.Audio.AudioColumns.YEAR,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BITRATE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val tracks = ArrayList<Track>()
        resolver.query(collection, projection, "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0", null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM)
            val genre = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.GENRE)
            val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.YEAR)
            val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val bitrate = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BITRATE)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val fileName = cursor.getString(name).orEmpty()
                tracks += Track(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(id)).toString(),
                    title = cursor.getString(title).cleanTag().ifEmpty { fileName.substringBeforeLast('.') },
                    artist = cursor.getString(artist).cleanTag(),
                    album = cursor.getString(album).cleanTag(),
                    genre = cursor.getString(genre).cleanTag(),
                    year = cursor.getInt(year),
                    trackNumber = cursor.getInt(track),
                    durationMs = cursor.getLong(duration),
                    bitrateKbps = (cursor.getLong(bitrate) / 1000).toInt(),
                    fileName = fileName,
                    sizeBytes = cursor.getLong(size),
                )
            }
        }
        return tracks
    }
}

/** MediaStore reports missing tags as "<unknown>"; treat that as empty. */
private fun String?.cleanTag(): String =
    this?.trim()?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty()
