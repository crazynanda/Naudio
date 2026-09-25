package com.naudio.data.repository

import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FavoritesRepositoryTest {
    private val fakeDao = FakeTrackDao()
    private val repository = FavoritesRepository(fakeDao)

    @Test
    fun `observeFavorites emits mapped tracks`() = runTest {
        fakeDao.state.value = listOf(
            TrackMapper.toEntity(Track("1", "local", "Title", "Artist")),
        )
        val result = repository.observeFavorites().first()
        assertEquals(1, result.size)
        assertEquals("Title", result[0].title)
    }

    @Test
    fun `favoriting upserts full metadata`() = runTest {
        val track = Track("1", "local", "Title", "Artist", durationMs = 5_000L)
        repository.toggleFavorite(track, true)
        fakeDao.state.value = listOf(TrackMapper.toEntity(track))
        val favorites = repository.observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("local", favorites[0].providerId)
    }

    @Test
    fun `unfavoriting keeps metadata intact`() = runTest {
        val track = Track("1", "local", "Title", "Artist", durationMs = 5_000L)
        repository.toggleFavorite(track, true)
        repository.toggleFavorite(track, false)
        val favorites = repository.observeFavorites().first()
        assertTrue(favorites.isEmpty())
        assertEquals("local", fakeDao.lastProviderId)
        assertEquals("1", fakeDao.lastTrackId)
    }

    @Test
    fun `savedAt is null when not favorited`() = runTest {
        val track = Track("1", "local", "Title", "Artist")
        repository.toggleFavorite(track, false)
        assertEquals(null, fakeDao.lastSavedAt)
    }

    @Test
    fun `favorite then unfavorite restores empty list`() = runTest {
        val track = Track("1", "local", "Title", "Artist")
        repository.toggleFavorite(track, true)
        repository.toggleFavorite(track, false)
        assertEquals(0, repository.observeFavorites().first().size)
    }

    @Test
    fun `favoriting multiple times performs full upserts`() = runTest {
        val track = Track("1", "local", "Title", "Artist")
        repository.toggleFavorite(track, true)
        repository.toggleFavorite(track, true)
        assertTrue(fakeDao.upsertWasCalled)
        assertEquals("local", fakeDao.lastProviderId)
    }

    private class FakeTrackDao : TrackDao {
        val state = MutableStateFlow<List<TrackEntity>>(emptyList())
        var lastProviderId: String? = null
        var lastTrackId: String? = null
        var lastSavedAt: Long? = null
        var lastIsFavorite: Boolean? = null
        var upsertWasCalled = false
        var updateWasCalled = false

        override fun observeFavorites() = state

        override suspend fun upsertTrack(track: TrackEntity) {
            upsertWasCalled = true
            lastProviderId = track.providerId
            lastTrackId = track.id
        }

        override suspend fun updateFavorite(
            providerId: String,
            trackId: String,
            isFavorite: Boolean,
            savedAt: Long?,
        ) {
            updateWasCalled = true
            lastProviderId = providerId
            lastTrackId = trackId
            lastIsFavorite = isFavorite
            lastSavedAt = savedAt
        }
    }
}
