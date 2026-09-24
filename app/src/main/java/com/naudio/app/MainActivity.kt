package com.naudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naudio.app.di.AppContainer
import com.naudio.app.ui.HomeScreen
import com.naudio.app.ui.HomeViewModel
import com.naudio.app.ui.PlaybackViewModel
import com.naudio.app.ui.theme.NaudioTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NaudioApplication).container
        setContent {
            NaudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeRoute(container = container)
                }
            }
        }
    }
}

@Composable
private fun HomeRoute(container: AppContainer) {
    val viewModel: HomeViewModel = viewModel {
        HomeViewModel(container.libraryRepository)
    }
    val playbackViewModel: PlaybackViewModel = viewModel {
        PlaybackViewModel(
            playbackController = container.playbackController,
            libraryRepository = container.libraryRepository,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        playerState = playerState,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::onRetry,
        onTrackSelected = playbackViewModel::onTrackSelected,
        onTogglePlayPause = playbackViewModel::onTogglePlayPause,
        onSeek = playbackViewModel::onSeek,
    )
}
