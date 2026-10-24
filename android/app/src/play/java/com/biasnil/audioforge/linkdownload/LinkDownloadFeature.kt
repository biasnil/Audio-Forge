package com.biasnil.audioforge.linkdownload

import androidx.compose.runtime.Composable
import com.biasnil.audioforge.data.Acknowledgement
import com.biasnil.audioforge.data.LinkTarget

/**
 * The Play version's stand-in for Download from link (see src/github):
 * Google Play doesn't allow downloading from YouTube and similar sites, so
 * here there's no button, no page and no downloader.
 */
object LinkDownloadFeature {
    const val AVAILABLE = false

    val acknowledgements: List<Acknowledgement> = emptyList()

    @Composable
    fun HeaderButton(onClick: () -> Unit) = Unit

    @Composable
    fun Page(target: LinkTarget, sharedLink: String?, onBack: () -> Unit) = Unit
}
