package com.biasnil.audioforge

import android.app.Application

class AudioForgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.library.refresh()
    }
}
