package com.biasnil.audioforge.data

import com.biasnil.audioforge.linkdownload.LinkDownloadFeature

/**
 * Settings -> About -> Acknowledgements: what the app is built with.
 *
 * Kept by hand. Update it whenever gradle/libs.versions.toml (or the
 * dependencies in app/build.gradle.kts) change.
 */
enum class License(val id: String, val title: String) {
    Apache2("apache-2.0", "Apache License 2.0"),
    Lgpl21("lgpl-2.1", "GNU Lesser General Public License 2.1"),
    Mit("mit", "MIT License"),
    Gpl3("gpl-3.0", "GNU General Public License 3.0"),
    Unlicense("unlicense", "The Unlicense"),
    Psf2("psf-2.0", "Python Software Foundation License 2.0"),
}

/** A library (with its [license]) or an online service ([license] = null). */
data class Acknowledgement(val name: String, val url: String, val license: License?)

val ACKNOWLEDGEMENTS: List<Acknowledgement> = LinkDownloadFeature.acknowledgements + listOf(
    Acknowledgement("AndroidX Core, Lifecycle, Activity", "https://developer.android.com/jetpack/androidx", License.Apache2),
    Acknowledgement("Jetpack Compose, Material 3", "https://developer.android.com/compose", License.Apache2),
    Acknowledgement("AndroidX Media3 (ExoPlayer, HLS, Session, UI)", "https://github.com/androidx/media", License.Apache2),
    Acknowledgement("Kotlin, kotlinx.coroutines", "https://kotlinlang.org", License.Apache2),
    Acknowledgement("jaudiotagger", "https://bitbucket.org/ijabz/jaudiotagger", License.Lgpl21),
    Acknowledgement("jump3r (LAME MP3 encoder)", "https://github.com/Sciss/jump3r", License.Lgpl21),
    Acknowledgement("Material Design icons", "https://github.com/google/material-design-icons", License.Apache2),
    Acknowledgement("LiteRT", "https://ai.google.dev/edge/litert", License.Apache2),
    Acknowledgement("AutoEq (headphone correction profiles)", "https://github.com/jaakkopasanen/AutoEq", License.Mit),
    Acknowledgement("YAMNet", "https://github.com/tensorflow/models/tree/master/research/audioset/yamnet", License.Apache2),
    Acknowledgement("LRCLIB", "https://lrclib.net", null),
    Acknowledgement("Musixmatch", "https://www.musixmatch.com", null),
    Acknowledgement("Apple iTunes Search API (podcasts, missing tags and covers)", "https://performance-partners.apple.com/search-api", null),
    Acknowledgement("Deezer API (missing tags and covers)", "https://developers.deezer.com/api", null),
    Acknowledgement("Radio Browser", "https://www.radio-browser.info", null),
    Acknowledgement("LibriVox (free audiobooks, read by volunteers)", "https://librivox.org", null),
    Acknowledgement("Internet Archive (free audiobook search and downloads)", "https://archive.org", null),
)
