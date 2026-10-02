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
        // M17: start listening-session accounting for the whole application
        // lifetime, before any UI exists. Touching the lazy here is what makes
        // history independent of which surface started playback — the Home
        // screen, an Auto session or a restored queue are all accounted for —
        // and it holds no Activity reference, so it survives navigation. The
        // tracker's coroutine is a child of the container's application scope
        // and is cancelled with it.
        container.historyTracker
    }

    override val autoPlaybackBridge: AutoPlaybackBridge
        get() = container.autoPlaybackBridge

    override val autoBrowseTree: AutoBrowseTreeProvider
        get() = container.autoBrowseTree
}
