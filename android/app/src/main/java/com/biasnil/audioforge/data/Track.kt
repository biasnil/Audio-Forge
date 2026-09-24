package com.biasnil.audioforge.data

/** One playable file, from the phone's music library or from an added folder. */
data class Track(
    /**
     * content:// URI -- a MediaStore URI for the phone library, a document URI
     * for added folders. Also the track's identity: playlists store these.
     */
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: Int = 0,
    /** disc * 1000 + track (MediaStore's own encoding); 0 = unknown. */
    val trackNumber: Int = 0,
    val durationMs: Long = 0,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val fileName: String = "",
    val sizeBytes: Long = 0,
    /** Tree URI of the added folder this came from; null = phone music library. */
    val sourceFolder: String? = null,
    /** cover.jpg / folder.jpg / ... next to the file (added folders only) -- used when there's no embedded art. */
    val folderCoverUri: String? = null,
    /** Same-name .lrc (or .txt) next to the file (added folders only) -- the desktop's sidecar lyrics. */
    val sidecarLyricsUri: String? = null,
)
