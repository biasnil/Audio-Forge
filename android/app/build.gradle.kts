import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.biasnil.audioforge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biasnil.audioforge"
        // Android 12+. The only native code is LiteRT (genre detection), whose
        // .so files are 16 KB aligned, so the app runs on both 4 KB and 16 KB
        // memory page size devices (check: zipalign -c -P 16 -v 4 on the APK).
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        // Each Android release is tagged "Androidv<versionName>" on GitHub, which
        // the in-app update check (Settings -> About -> Version) compares with this.
        versionName = "1.4.3"
        ndk {
            // The ABIs LiteRT ships (no 32-bit x86, which no phone this app supports uses).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Two builds of the same app:
    //   github: the APK attached to GitHub releases (./gradlew assembleGithubRelease).
    //           Also has Download from link (src/github: yt-dlp), which Play doesn't allow.
    //   play:   the Play Store bundle (./gradlew bundlePlayRelease). Play doesn't
    //           allow apps to point users at updates from elsewhere either.
    //           src/play has an empty stand-in for Download from link.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
        }
    }

    // Release signing, only if android/keystore.properties exists (it is
    // gitignored; see keystore.properties.example). Without it, release
    // builds come out unsigned, as before.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            // R8: shrinks, optimizes and obfuscates release builds (Play flags apps
            // that don't). Keep rules for reflection-using libraries: proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // VERSION_NAME for Settings -> About
    }

    androidResources {
        // Lists the translated languages (res/values-*) for the system, so on
        // Android 13+ the app also appears in Settings -> Apps -> Language.
        // The untranslated res/values is English (res/resources.properties).
        generateLocaleConfig = true
        // The genre model is memory-mapped straight from the APK, which needs it stored uncompressed.
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            // LiteRT 2.2 bundles a GPU accelerator; genre detection only uses the CPU.
            excludes += "**/libLiteRtClGlAccelerator.so"
        }
    }

    lint {
        // Android Auto voice search ("play X on AudioForge") isn't supported
        // yet; adding the intent filter without handling the search would
        // advertise a feature the app doesn't have.
        disable += "MissingIntentFilterForMediaSearch"
        // The GitHub version's downloader brings in AppCompat, whose lint wants
        // app:tint -- which home-screen widgets (RemoteViews) can't use.
        disable += "UseAppTint"
    }
}

androidComponents {
    // yt-dlp's Python and FFmpeg run from the app's native library folder, so
    // the GitHub version's .so files are installed extracted, not left in the APK.
    onVariants(selector().withFlavor("store" to "github")) { variant ->
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls) // radio stations that stream HLS (.m3u8)
    implementation(libs.androidx.media3.ui) // PlayerView, for the video wallpaper
    // Tag editing (the desktop uses TagLib; this is the pure-Java equivalent).
    implementation(libs.jaudiotagger)
    // MP3 encoding for the song editor (LAME in plain Java, LGPL).
    implementation(libs.jump3r)
    // On-device genre detection (YAMNet). LiteRT is TensorFlow Lite's new name, same Interpreter API.
    implementation(libs.litert)
    constraints {
        // LiteRT brings in fragment 1.1.0, which Play Console flags as outdated.
        implementation(libs.androidx.fragment)
    }
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Download from link, GitHub version only (never in the Play build).
    "githubImplementation"(libs.youtubedl.library)
    "githubImplementation"(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    // Android's org.json is only a stub in JVM unit tests; this is the real one (tests only).
    testImplementation(libs.json)
}
