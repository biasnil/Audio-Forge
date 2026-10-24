package com.biasnil.audioforge.linkdownload

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Acknowledgement
import com.biasnil.audioforge.data.License
import com.biasnil.audioforge.data.LinkTarget
import com.biasnil.audioforge.ui.AppIcons

/**
 * Download from link, as the rest of the app sees it. This is the GitHub
 * version's; the Play version has a stand-in with the same names that does
 * nothing (src/play), so none of this is in the Play build.
 */
object LinkDownloadFeature {
    const val AVAILABLE = true

    val acknowledgements: List<Acknowledgement> = listOf(
        Acknowledgement("youtubedl-android", "https://github.com/yausername/youtubedl-android", License.Gpl3),
        Acknowledgement("yt-dlp", "https://github.com/yt-dlp/yt-dlp", License.Unlicense),
        Acknowledgement("Python", "https://www.python.org", License.Psf2),
        Acknowledgement("FFmpeg", "https://ffmpeg.org", License.Gpl3),
        Acknowledgement("QuickJS", "https://bellard.org/quickjs", License.Mit),
        Acknowledgement("Media Downloader Toolkit", "https://github.com/biasnil/Media-Downloader-Toolkit", null),
    )

    /** The title-bar button on Songs and Audiobooks that opens Download from link. */
    @Composable
    fun HeaderButton(onClick: () -> Unit) {
        IconButton(onClick = onClick) {
            Icon(AppIcons.Download, contentDescription = stringResource(R.string.link_title))
        }
    }

    @Composable
    fun Page(target: LinkTarget, sharedLink: String?, onBack: () -> Unit) = LinkDownloadPage(target, sharedLink, onBack)
}
