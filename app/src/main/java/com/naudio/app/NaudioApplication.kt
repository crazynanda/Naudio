package com.naudio.app

import android.app.Application
import com.naudio.app.di.AppContainer

class NaudioApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
