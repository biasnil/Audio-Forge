package com.biasnil.audioforge.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://example.com/$tag"}"""

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    /** The release list as it was on 2026-10-20: desktop and Android tags mixed. */
    private val real = list(
        release("1.3.0"), release("1.2.0"), release("Androidv1.0.1"),
        release("Androidv1.0.0"), release("1.1.1a"), release("1.1.1"), release("1.1.0"), release("1.0.0"),
    )

    @Test
    fun desktopReleasesAreIgnored() {
        // /releases/latest would say 1.3.0; only the Androidv tags count.
        val update = newestAndroidRelease(real, "1.0.0")
        assertEquals("1.0.1", update?.version)
        assertEquals("https://example.com/Androidv1.0.1", update?.url)
    }

    @Test
    fun sameVersionIsUpToDate() {
        assertNull(newestAndroidRelease(real, "1.0.1"))
    }

    @Test
    fun newerInstalledVersionIsUpToDate() {
        assertNull(newestAndroidRelease(real, "1.0.2"))
    }

    @Test
    fun versionsCompareAsNumbers() {
        val json = list(release("Androidv1.0.9"), release("Androidv1.0.10"), release("Androidv1.0.2"))
        assertEquals("1.0.10", newestAndroidRelease(json, "1.0.9")?.version)
    }

    @Test
    fun draftsAndPrereleasesAreSkipped() {
        val json = list(release("Androidv2.0.0", draft = true), release("Androidv1.5.0", prerelease = true), release("Androidv1.0.3"))
        assertEquals("1.0.3", newestAndroidRelease(json, "1.0.2")?.version)
    }

    @Test
    fun malformedTagsAreSkipped() {
        val json = list(release("Androidv"), release("Androidvbeta"), release("Androidv1..2"), release("Androidv1.1"))
        assertEquals("1.1", newestAndroidRelease(json, "1.0.2")?.version)
    }

    @Test
    fun tagPrefixIgnoresCaseAndMissingPartsAreZero() {
        val json = list(release("androidV1.1"))
        assertNull(newestAndroidRelease(json, "1.1.0"))
        assertEquals("1.1", newestAndroidRelease(json, "1.0.5")?.version)
    }

    @Test
    fun trailingLetterIsIgnored() {
        assertNull(newestAndroidRelease(list(release("Androidv1.0.2a")), "1.0.2"))
    }

    @Test
    fun noAndroidReleaseIsUpToDate() {
        assertNull(newestAndroidRelease(list(release("1.3.0")), "1.0.0"))
        assertNull(newestAndroidRelease("[]", "1.0.0"))
    }

    @Test(expected = JSONException::class)
    fun badJsonThrows() {
        // UpdateChecker turns this into UpdateState.Failed.
        newestAndroidRelease("""{"message":"API rate limit exceeded"}""", "1.0.0")
    }

    @Test
    fun compareVersionsHandlesBadInput() {
        assertEquals(1, compareVersions("1.0.10", "1.0.9")?.coerceIn(-1, 1))
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertNull(compareVersions("abc", "1.0.0"))
    }
}
