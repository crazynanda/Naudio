package com.naudio.data.repository

import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.Track
import com.naudio.data.mapper.TrackMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRepositoryTest {
    private val fakeDao = FakeTrackDao()
    private val repository = FavoritesRepository(fakeDao)

    @Test
    fun `observeFavorites emits mapped tracks`() = runTest {
        fakeDao.state.value = listOf(
            TrackMapper.toEntity(Track("1", "local", "Title", "Artist")).copy(isFavorite = true),
        )
        val result = repository.observeFavorites().first()
        assertEquals(1, result.size)
        assertEquals("Title", result[0].title)
    }

    @Test
    fun `favoriting upserts full metadata`() = runTest {
        val track = Track("1", "local", "Title", "Artist", durationMs = 5_000L)
        repository.toggleFavorite(track, true)
        val favorites = repository.observeFavorites().first()
        assertEquals(1, favorites.size)
        assertEquals("local", favorites[0].providerId)
    }

    /**
     * Regression: favoriting must persist is_favorite = 1. The upsert path
     * maps the domain track to a non-favorited entity; with Room REPLACE that
     * default would previously store an UN-favorited row, so the track never
     * appeared in favorites.
     */
    @Test
    fun `favoriting persists the favorite flag so the track is observable`() = runTest {
        val track = Track("42", "itunes", "Get Lucky", "Daft Punk", durationMs = 249_000L)
        repository.toggleFavorite(track, true)
        val favorites = repository.observeFavorites().first()
        assertEquals(listOf(track), favorites)
        val row = fakeDao.state.value.single()
        assertTrue(row.isFavorite)
        assertNotNull(row.savedAt)
    }

    @Test
    fun `unfavoriting keeps metadata intact`() = runTest {
        val track = Track("1", "local", "Title", "Artist", durationMs = 5_000L)
        repository.toggleFavorite(track, true)
        repository.toggleFavorite(track, false)
        val favorites = repository.observeFavorites().first()
        assertTrue(favorites.isEmpty())
        // The row itself is retained (metadata intact), only un-flagged.
        val row = fakeDao.state.value.single()
        assertEquals("Title", row.title)
        assertEquals(false, row.isFavorite)
        assertEquals(null, row.savedAt)
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
        // Still exactly one row for the composite identity.
        assertEquals(1, fakeDao.state.value.size)
    }

    /** Composite (providerId, trackId) identity: same id across providers never collides. */
    @Test
    fun `same track id from different providers coexist as separate favorites`() = runTest {
        val local = Track("1", "local", "Local One", "Artist A")
        val itunes = Track("1", "itunes", "Itunes One", "Artist B")
        repository.toggleFavorite(local, true)
        repository.toggleFavorite(itunes, true)
        assertEquals(setOf("local" to "1", "itunes" to "1"), repository.observeFavoriteIds().first().map { it.providerId to it.trackId }.toSet())
        assertEquals(2, repository.observeFavorites().first().size)

        // Unfavoriting the local one leaves the iTunes favorite untouched.
        repository.toggleFavorite(local, false)
        val remaining = repository.observeFavorites().first()
        assertEquals(1, remaining.size)
        assertEquals("itunes", remaining[0].providerId)
    }

    private class FakeTrackDao : TrackDao {
        /** The rows "stored in Room"; observeFavorites filters like the real query. */
        val state = MutableStateFlow<List<TrackEntity>>(emptyList())
        var lastProviderId: String? = null
        var lastTrackId: String? = null
        var lastSavedAt: Long? = null
        var lastIsFavorite: Boolean? = null
        var upsertWasCalled = false
        var updateWasCalled = false

        override fun observeFavorites() = state.map { rows ->
            rows.filter { it.isFavorite }.sortedByDescending { it.savedAt ?: 0L }
        }

        /** Models @Insert(REPLACE): replaces the row with the same composite key. */
        override suspend fun upsertTrack(track: TrackEntity) {
            upsertWasCalled = true
            lastProviderId = track.providerId
            lastTrackId = track.id
            state.value = state.value
                .filterNot { it.id == track.id && it.providerId == track.providerId } + track
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
            state.value = state.value.map { row ->
                if (row.id == trackId && row.providerId == providerId) {
                    row.copy(isFavorite = isFavorite, savedAt = savedAt)
                } else {
                    row
                }
            }
        }
    }
}
