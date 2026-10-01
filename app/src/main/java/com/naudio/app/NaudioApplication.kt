package com.naudio.app

import android.app.Application
import com.naudio.app.di.AppContainer
import com.naudio.core.player.AutoBrowseTreeProvider
import com.naudio.core.player.AutoPlaybackBridge
import com.naudio.core.player.PlaybackDependenciesProvider

/**
 * Application-lifetime DI host. Implements [PlaybackDependenciesProvider] so
 * NaudioPlaybackService (in :core:player, below the app layer) can obtain the
 * Auto bridge/browse tree when the system binds it — Application.onCreate
 * always completes before any service is created.
 */
class NaudioApplication : Application(), PlaybackDependenciesProvider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val autoPlaybackBridge: AutoPlaybackBridge
        get() = container.autoPlaybackBridge

    override val autoBrowseTree: AutoBrowseTreeProvider
        get() = container.autoBrowseTree
}
