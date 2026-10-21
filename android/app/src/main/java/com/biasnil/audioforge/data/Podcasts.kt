package com.biasnil.audioforge.data

// Podcasts: songs in folders marked "Podcast" in the Folders tab. They only
// show in the Podcasts tab (not Songs, Albums, shuffle, smart playlists,
// duplicates) and are never analyzed. Plain Kotlin, tested on the JVM.

/** One show in the Podcasts tab and its episodes, newest first. */
data class PodcastShow(val name: String, val episodes: List<Track>)

/**
 * Where an episode sits under its podcast folder: [subfolder] is the first
 * folder below it ("" if the file is directly in it), [rootName] the
 * podcast folder's own name.
 */
data class PodcastPlace(val rootName: String, val subfolder: String)

/**
 * The show an episode belongs to: its subfolder under the podcast folder;
 * with no subfolder, its Album tag; with neither, the podcast folder's name.
 */
fun showName(track: Track, place: PodcastPlace): String =
    place.subfolder.ifEmpty { track.album.trim() }.ifEmpty { place.rootName }

/** Episodes grouped into shows, A-Z; each show's episodes newest first (then A-Z by title). */
fun podcastShows(episodes: List<Track>, placeOf: (Track) -> PodcastPlace): List<PodcastShow> =
    episodes.groupBy { showName(it, placeOf(it)) }
        .map { (name, list) ->
            PodcastShow(
                name,
                list.sortedWith(compareByDescending<Track> { it.dateAddedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/**
 * Whether [track] is a podcast: Android files it as one, or it's in a podcast
 * folder -- an added folder in [podcastFolders] (by tree URI), or a
 * phone-library folder in it or inside one (by folder key).
 */
fun isPodcast(track: Track, podcastFolders: Set<String>): Boolean {
    if (track.systemPodcast) return true
    if (podcastFolders.isEmpty()) return false
    val source = track.sourceFolder
    if (source != null) return source in podcastFolders
    return podcastFolders.any { isInFolder(track.folderKey, it) }
}

/**
 * A phone-library episode's place: below the deepest podcast folder that
 * holds it. "external_primary|Podcasts/Show A/" under "external_primary|Podcasts/"
 * -> subfolder "Show A", root "Podcasts".
 */
fun phonePodcastPlace(folderKey: String, podcastFolders: Set<String>): PodcastPlace {
    val root = podcastFolders.filter { isInFolder(folderKey, it) }.maxByOrNull { it.length }
        ?: return PodcastPlace(folderPath(folderKey).substringAfterLast('/'), "")
    val below = folderKey.removePrefix(root).trim('/')
    return PodcastPlace(folderPath(root).substringAfterLast('/'), below.substringBefore('/'))
}

/**
 * An added-folder episode's place, from document ids: the tree's
 * ("primary:Podcasts") and the file's ("primary:Podcasts/Show A/ep1.mp3").
 * Providers with opaque ids give no subfolder (the Album tag is used then).
 */
fun documentPodcastPlace(treeDocumentId: String, fileDocumentId: String): PodcastPlace {
    val rootName = treeDocumentId.substringAfter(':', "").trimEnd('/').substringAfterLast('/')
        .ifEmpty { treeDocumentId.substringBefore(':') }
    // "primary:" (a whole storage volume) has no slash before its children.
    val prefix = if (treeDocumentId.endsWith(':')) treeDocumentId else treeDocumentId.trimEnd('/') + "/"
    if (!fileDocumentId.startsWith(prefix)) return PodcastPlace(rootName, "")
    val relative = fileDocumentId.removePrefix(prefix)
    return PodcastPlace(rootName, if ('/' in relative) relative.substringBefore('/') else "")
}
