package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastsTest {

    private fun episode(title: String, album: String = "", added: Long = 0, folderKey: String = "", source: String? = null) =
        Track(uri = "content://e/$title", title = title, album = album, dateAddedMs = added, folderKey = folderKey, sourceFolder = source)

    @Test fun subfolderIsTheShow() =
        assertEquals("Show A", showName(episode("ep1", album = "Album tag"), PodcastPlace("Podcasts", "Show A")))

    @Test fun withoutSubfolderTheAlbumTag() =
        assertEquals("Album tag", showName(episode("ep1", album = " Album tag "), PodcastPlace("Podcasts", "")))

    @Test fun withNeitherTheFolderName() =
        assertEquals("Podcasts", showName(episode("ep1"), PodcastPlace("Podcasts", "")))

    @Test fun showsAToZAndEpisodesNewestFirst() {
        val a1 = episode("a1", album = "Beta", added = 100)
        val a2 = episode("a2", album = "Beta", added = 300)
        val a3 = episode("a3", album = "Beta", added = 200)
        val b1 = episode("b1", album = "alpha", added = 50)
        val shows = podcastShows(listOf(a1, a2, a3, b1)) { PodcastPlace("Root", "") }
        assertEquals(listOf("alpha", "Beta"), shows.map { it.name })
        assertEquals(listOf("a2", "a3", "a1"), shows[1].episodes.map { it.title })
    }

    @Test fun addedFolderPodcastsByTreeUri() {
        val tree = "content://tree/primary%3APodcasts"
        assertTrue(isPodcast(episode("e", source = tree), setOf(tree)))
        assertFalse(isPodcast(episode("e", source = "content://tree/primary%3AMusic"), setOf(tree)))
    }

    @Test fun phoneFoldersIncludeSubfolders() {
        val folders = setOf("external_primary|Podcasts/")
        assertTrue(isPodcast(episode("e", folderKey = "external_primary|Podcasts/"), folders))
        assertTrue(isPodcast(episode("e", folderKey = "external_primary|Podcasts/Show A/"), folders))
        assertFalse(isPodcast(episode("e", folderKey = "external_primary|Podcasts2/"), folders))
        assertFalse(isPodcast(episode("e", folderKey = "external_primary|Music/"), emptySet()))
    }

    @Test fun androidFlaggedPodcastsAlwaysCount() {
        val flagged = episode("e", folderKey = "external_primary|Podcasts/").copy(systemPodcast = true)
        assertTrue(isPodcast(flagged, emptySet()))
        assertEquals(PodcastPlace("Podcasts", ""), phonePodcastPlace(flagged.folderKey, emptySet()))
    }

    @Test fun downloadedEpisodesUseTheirShowFolder() {
        // Downloads land in Podcasts/<Show>/; that beats an unhelpful Album tag ("Podcast").
        val place = phonePodcastPlace("external_primary|Podcasts/Radiolab/", emptySet())
        assertEquals(PodcastPlace("Podcasts", "Radiolab"), place)
        assertEquals("Radiolab", showName(episode("ep", album = "Podcast"), place))
        // Only the top-level Podcasts folder.
        assertEquals(PodcastPlace("Talk", ""), phonePodcastPlace("external_primary|Music/Podcasts/Talk/", emptySet()))
    }

    @Test fun phonePlace() {
        val folders = setOf("external_primary|Podcasts/")
        assertEquals(PodcastPlace("Podcasts", "Show A"), phonePodcastPlace("external_primary|Podcasts/Show A/Season 1/", folders))
        assertEquals(PodcastPlace("Podcasts", ""), phonePodcastPlace("external_primary|Podcasts/", folders))
        // The deepest marked folder wins.
        val nested = folders + "external_primary|Podcasts/Show A/"
        assertEquals(PodcastPlace("Show A", "Season 1"), phonePodcastPlace("external_primary|Podcasts/Show A/Season 1/", nested))
    }

    @Test fun documentPlace() {
        assertEquals(PodcastPlace("Podcasts", "Show A"), documentPodcastPlace("primary:Podcasts", "primary:Podcasts/Show A/ep1.mp3"))
        assertEquals(PodcastPlace("Podcasts", ""), documentPodcastPlace("primary:Podcasts", "primary:Podcasts/ep1.mp3"))
        // Storage root, and providers with opaque ids.
        assertEquals(PodcastPlace("primary", "Show"), documentPodcastPlace("primary:", "primary:Show/ep.mp3"))
        assertEquals(PodcastPlace("Talks", ""), documentPodcastPlace("primary:Talks", "msf:1234"))
    }
}
