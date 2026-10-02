package com.naudio.app.ui

import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.data.repository.LyricsRepository
import com.naudio.provider.api.LyricsProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial track loads lyrics`() = runTest {
        val track = Track("1", "test", "Title", "Artist", null, null, 100L)
        val playerStateFlow = MutableStateFlow(PlayerState(track = track))
        
        val controller = object : PlaybackController {
            override val state: StateFlow<PlayerState> = playerStateFlow.asStateFlow()
            override fun load(track: Track, source: com.naudio.core.model.AudioSource) {}
            override fun play() {}
            override fun pause() {}
            override fun stop() {}
            override fun seekTo(positionMs: Long) {}
            // M16: unused by the lyrics ViewModel, which only reads state.
            override fun setShuffleModeEnabled(enabled: Boolean) {}
            override fun setRepeatMode(repeatMode: Int) {}
            override fun release() {}
        }
        
        val provider = object : LyricsProvider {
            override val id = ProviderId("test")
            override val displayName = "Test"
            override suspend fun getLyrics(track: Track): Lyrics = Lyrics.NotFound
        }

        val viewModel = LyricsViewModel(controller, LyricsRepository(provider))

        val states = mutableListOf<LyricsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.lyricsState.toList(states)
        }
        
        advanceUntilIdle()

        assertTrue(states.any { it is LyricsState.Loading })
        assertTrue(states.last() is LyricsState.Success)
        assertEquals(Lyrics.NotFound, (states.last() as LyricsState.Success).lyrics)
    }
}
