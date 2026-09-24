package com.biasnil.audioforge.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Fills [base]'s tag fields from the file itself (MediaMetadataRetriever):
 * used for added-folder tracks during a scan, and for any track right
 * after its tags are edited (MediaStore re-indexes a changed file on its
 * own schedule). Keeps [base]'s values where the file has none; throws if
 * the file can't be read at all.
 */
fun readTags(context: Context, base: Track): Track =
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, Uri.parse(base.uri))
        fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
        base.copy(
            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { base.fileName.substringBeforeLast('.').ifEmpty { base.title } },
            artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
            year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
            trackNumber = discTrack(
                tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
            ),
            durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: base.durationMs,
            bitrateKbps = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull()?.let { (it / 1000).toInt() }
                ?: base.bitrateKbps,
            sampleRateHz = tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: base.sampleRateHz,
        )
    }

/** "1/2" + "3/12" -> 1003, MediaStore's disc * 1000 + track encoding. */
internal fun discTrack(disc: String, track: String): Int {
    val trackNumber = track.substringBefore('/').trim().toIntOrNull() ?: return 0
    val discNumber = disc.substringBefore('/').trim().toIntOrNull() ?: 0
    return discNumber * 1000 + trackNumber
}
