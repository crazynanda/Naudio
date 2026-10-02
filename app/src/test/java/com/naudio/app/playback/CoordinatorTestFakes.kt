package com.naudio.app.playback

import com.naudio.core.database.dao.QueueDao
import com.naudio.core.database.dao.TrackDao
import com.naudio.core.database.entity.QueueEntity
import com.naudio.core.database.entity.QueueStateEntity
import com.naudio.core.database.entity.TrackEntity
import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.player.PlaybackController
import com.naudio.core.player.PlayerState
import com.naudio.core.player.PlaybackStatus
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.FavoritesRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.data.repository.QueueRepository
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared deterministic fakes for coordinator/ViewModel playback tests. */

internal fun testTracks() = listOf(
    Track("a1", "local", "Track A", "Artist A"),
    Track("b1", "local", "Track B", "Artist B"),
    Track("c1", "local", "Track C", "Artist C"),
)

internal fun ytmTrack(id: String = "y1") = Track(id, "ytmusic", "YTM B", "Artist Y")

internal fun testLibraryRepository(): LibraryRepository = LibraryRepository(
    ProviderRegistry(
        providers = listOf(
            FakeMetadata(ProviderId("local")),
            FakeMetadata(ProviderId("itunes")),
            FakeMetadata(ProviderId("ytmusic")),
        ),
        playbackProviders = listOf(
            LocalPlayback(),
            RecordingPlayback(ProviderId("itunes")),
        ),
    ),
)

internal fun testQueueRepository(trackDao: TrackDao, queueDao: QueueDao) =
    QueueRepository(queueDao, trackDao)

internal fun testFavoritesRepository(trackDao: TrackDao) = FavoritesRepository(trackDao)

internal class FakeMetadata(override val id: ProviderId) : MetadataProvider {
    override val displayName: String = id.value
    override suspend fun searchTracks(query: String, token: PageToken?, limit: Int): Page<Track> =
        Page(emptyList(), nextToken = null)

    override suspend fun lookupTrack(id: String): Track? = null
}

/** Resolves every local track to a fake local source. */
internal class LocalPlayback : PlaybackProvider {
    override val id: ProviderId = ProviderId("local")
    override suspend fun resolve(track: Track): AudioSource? =
        if (track.providerId == "local") AudioSource.Local("content://fake/${track.id}") else null
}

internal class RecordingPlayback(override val id: ProviderId) : PlaybackProvider {
    override suspend fun resolve(track: Track): AudioSource? = null
}

/** REPLACE-faithful in-memory TrackDao for favorite-state tests. */
internal class InMemoryTrackDao : TrackDao {
    val rows = mutableMapOf<Pair<String, String>, TrackEntity>()
    private val state = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun publish() {
        state.value = rows.values
            .filter { it.isFavorite }
            .sortedByDescending { it.savedAt ?: 0L }
    }

    override fun observeFavorites() = state

    override suspend fun byIds(providerIds: List<String>, trackIds: List<String>): List<TrackEntity> =
        rows.values.filter { it.providerId in providerIds && it.id in trackIds }

    override suspend fun upsertTrack(track: TrackEntity) {
        rows[track.providerId to track.id] = track
        publish()
    }

    override suspend fun updateFavorite(
        providerId: String,
        trackId: String,
        isFavorite: Boolean,
        savedAt: Long?,
    ) {
        val key = providerId to trackId
        val existing = rows[key] ?: return
        rows[key] = existing.copy(isFavorite = isFavorite, savedAt = savedAt)
        publish()
    }
}

/** In-memory QueueDao with replace semantics matching the Room implementation. */
internal class FakeQueueDao : QueueDao {
    val items = MutableStateFlow<List<QueueEntity>>(emptyList())
    val state = MutableStateFlow<QueueStateEntity?>(null)

    override fun observeQueue(): Flow<List<QueueEntity>> = items

    override suspend fun clearQueue() {
        items.value = emptyList()
    }

    override suspend fun insertAll(items: List<QueueEntity>) {
        // REPLACE on the order_index primary key: a dense insert is a replace.
        this.items.value = items.sortedBy { it.orderIndex }
    }

    override fun observeState(): Flow<QueueStateEntity?> = state

    override suspend fun upsertState(state: QueueStateEntity) {
        this.state.value = state
    }
}

internal class FakePlaybackController : PlaybackController {
    private val _state = MutableStateFlow(PlayerState())
    override val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    var loadedTrack: Track? = null
    var loadCount = 0
    var playCalls = 0
    var pauseCalls = 0
    var seekPositions = mutableListOf<Long>()

    // M16: playback modes. The fake mirrors the real controller's contract:
    // a command updates the state, which is what the UI then observes.
    var setShuffleCalls = mutableListOf<Boolean>()
    var setRepeatCalls = mutableListOf<Int>()

    fun emitStatus(status: PlaybackStatus) {
        _state.value = _state.value.copy(status = status, track = loadedTrack)
    }

    /** M16: simulate Media3 looping the current item (REPEAT transition). */
    fun emitRepeatLoop(repeatMode: Int = _state.value.repeatMode) {
        _state.value = _state.value.copy(
            repeatMode = repeatMode,
            repeatLoopCount = _state.value.repeatLoopCount + 1,
        )
    }

    override fun load(track: Track, source: AudioSource) {
        loadCount++
        loadedTrack = track
        _state.value = _state.value.copy(status = PlaybackStatus.READY, isPlaying = true, track = track)
    }

    override fun play() {
        playCalls++
    }

    override fun pause() {
        pauseCalls++
    }
    override fun stop() {}
    override fun seekTo(positionMs: Long) {
        seekPositions.add(positionMs)
    }

    override fun setShuffleModeEnabled(enabled: Boolean) {
        setShuffleCalls.add(enabled)
        _state.value = _state.value.copy(shuffleModeEnabled = enabled)
    }

    override fun setRepeatMode(repeatMode: Int) {
        setRepeatCalls.add(repeatMode)
        _state.value = _state.value.copy(repeatMode = repeatMode)
    }

    override fun release() {}
}
