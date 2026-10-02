package com.naudio.data.repository

import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track
import com.naudio.provider.api.LyricsProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsRepositoryTest {
    private val fakeTrack = Track("id", "provider", "Title", "Artist", "Album", null, 1000L)

    @Test
    fun `caches success result`() = runTest {
        var callCount = 0
        val fakeProvider = object : LyricsProvider {
            override val id = ProviderId("test")
            override val displayName = "Test"
            override suspend fun getLyrics(track: Track): Lyrics {
                callCount++
                return Lyrics.NotFound
            }
        }

        val repo = LyricsRepository(fakeProvider)

        val result1 = repo.getLyrics(fakeTrack)
        val result2 = repo.getLyrics(fakeTrack)

        assertEquals(Lyrics.NotFound, result1)
        assertEquals(Lyrics.NotFound, result2)
        assertEquals(1, callCount)
    }

    @Test
    fun `does not cache transient failure`() = runTest {
        var callCount = 0
        val fakeProvider = object : LyricsProvider {
            override val id = ProviderId("test")
            override val displayName = "Test"
            override suspend fun getLyrics(track: Track): Lyrics {
                callCount++
                return Lyrics.Unavailable
            }
        }

        val repo = LyricsRepository(fakeProvider)

        val result1 = repo.getLyrics(fakeTrack)
        val result2 = repo.getLyrics(fakeTrack)

        assertEquals(Lyrics.Unavailable, result1)
        assertEquals(Lyrics.Unavailable, result2)
        assertEquals(2, callCount)
    }
}
