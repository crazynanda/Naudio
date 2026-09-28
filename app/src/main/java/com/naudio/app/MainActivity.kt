package com.naudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
        HomeViewModel(
            repository = container.libraryRepository,
            registry = container.providerRegistry,
        )
    }
    val playbackViewModel: PlaybackViewModel = viewModel {
        PlaybackViewModel(
            playbackController = container.playbackController,
            libraryRepository = container.libraryRepository,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val playbackError by playbackViewModel.playbackError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Lifecycle-safe media permission state: held in compose state, refreshed
    // when the composable re-enters composition (user may grant in Settings),
    // requested via the ActivityResult API only when the user asks — never per
    // recomposition.
    var audioPermissionGranted by remember {
        mutableStateOf(context.hasAudioPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { audioPermissionGranted = it || context.hasAudioPermission() }

    HomeScreen(
        state = state,
        playerState = playerState,
        playbackError = playbackError,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::onRetry,
        onTrackSelected = playbackViewModel::onTrackSelected,
        onTogglePlayPause = playbackViewModel::onTogglePlayPause,
        onSeek = playbackViewModel::onSeek,
        onPlaybackErrorShown = playbackViewModel::onErrorShown,
        audioPermissionGranted = audioPermissionGranted,
        onRequestAudioPermission = {
            permissionLauncher.launch(audioPermission())
        },
        onSelectProvider = viewModel::onSelectProvider,
    )
}

/** The media-read permission for this OS version (13+ uses the audio-scoped one). */
private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun android.content.Context.hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    audioPermission(),
) == PackageManager.PERMISSION_GRANTED
