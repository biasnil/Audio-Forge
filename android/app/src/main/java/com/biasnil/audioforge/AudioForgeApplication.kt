package com.biasnil.audioforge

import android.app.Application
import android.content.Context

class AudioForgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** The app language on Android 12 (13+ applies it itself). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.localized(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.library.refresh()
    }
}
