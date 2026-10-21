package com.biasnil.audioforge.data

/**
 * Settings -> About -> Acknowledgements: what the app is built with.
 *
 * Kept by hand. Update it whenever gradle/libs.versions.toml (or the
 * dependencies in app/build.gradle.kts) change.
 */
enum class License(val id: String, val title: String) {
    Apache2("apache-2.0", "Apache License 2.0"),
    Lgpl21("lgpl-2.1", "GNU Lesser General Public License 2.1"),
}

/** A library (with its [license]) or an online service ([license] = null). */
data class Acknowledgement(val name: String, val url: String, val license: License?)

val ACKNOWLEDGEMENTS: List<Acknowledgement> = listOf(
    Acknowledgement("AndroidX Core, Lifecycle, Activity", "https://developer.android.com/jetpack/androidx", License.Apache2),
    Acknowledgement("Jetpack Compose, Material 3", "https://developer.android.com/compose", License.Apache2),
    Acknowledgement("AndroidX Media3 (ExoPlayer, Session, UI)", "https://github.com/androidx/media", License.Apache2),
    Acknowledgement("Kotlin, kotlinx.coroutines", "https://kotlinlang.org", License.Apache2),
    Acknowledgement("jaudiotagger", "https://bitbucket.org/ijabz/jaudiotagger", License.Lgpl21),
    Acknowledgement("Material Design icons", "https://github.com/google/material-design-icons", License.Apache2),
    Acknowledgement("LRCLIB", "https://lrclib.net", null),
    Acknowledgement("Musixmatch", "https://www.musixmatch.com", null),
)
