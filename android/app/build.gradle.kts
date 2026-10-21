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
        // Android 12+. The app is pure Kotlin/Java (no native .so files), so
        // it runs unchanged on both 4 KB and 16 KB memory page size devices.
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    androidResources {
        // Lists the translated languages (res/values-*) for the system, so on
        // Android 13+ the app also appears in Settings -> Apps -> Language.
        // The untranslated res/values is English (res/resources.properties).
        generateLocaleConfig = true
    }

    lint {
        // Android Auto voice search ("play X on AudioForge") isn't supported
        // yet; adding the intent filter without handling the search would
        // advertise a feature the app doesn't have.
        disable += "MissingIntentFilterForMediaSearch"
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
    implementation(libs.androidx.media3.ui) // PlayerView, for the video wallpaper
    // Tag editing (the desktop uses TagLib; this is the pure-Java equivalent).
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
