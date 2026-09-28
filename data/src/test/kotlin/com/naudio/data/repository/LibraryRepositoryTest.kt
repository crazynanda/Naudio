package com.naudio.data.repository

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing contract: a Track is resolved by the playback provider registered
 * under [Track.providerId] — never by whichever metadata provider is active.
 */
class LibraryRepositoryTest {

    private class FakeMetadataProvider(override val id: ProviderId) : MetadataProvider {
        override val displayName: String = id.value
        override suspend fun searchTracks(
            query: String,
            token: PageToken?,
            limit: Int,
        ): Page<Track> = Page(emptyList(), nextToken = null)

        override suspend fun lookupTrack(id: String): Track? = null
    }

    /** Records every resolve call; yields a distinct source for resolvable ids. */
    private class FakePlaybackProvider(
        override val id: ProviderId,
        private val resolvableTrackIds: Set<String> = emptySet(),
    ) : PlaybackProvider {
        val resolvedTracks = mutableListOf<Track>()

        override suspend fun resolve(track: Track): AudioSource? {
            resolvedTracks += track
            return if (track.id in resolvableTrackIds) {
                AudioSource.Remote("https://example.invalid/${id.value}/${track.id}")
            } else {
                null
            }
        }
    }

    private fun track(id: String, providerId: String): Track =
        Track(id = id, providerId = providerId, title = "T", artist = "A")

    private fun registry(
        activeId: String,
        playbackProviders: List<PlaybackProvider>,
    ): ProviderRegistry = ProviderRegistry(
        providers = listOf(
            FakeMetadataProvider(ProviderId("itunes")),
            FakeMetadataProvider(ProviderId("local")),
            FakeMetadataProvider(ProviderId("default")),
            FakeMetadataProvider(ProviderId("ytmusic")),
        ),
        playbackProviders = playbackProviders,
    ).apply { activate(ProviderId(activeId)) }

    // 1. iTunes track resolves through the iTunes playback provider.
    @Test
    fun `itunes track routes to the itunes playback provider regardless of active provider`() = runTest {
        val itunesPlayback = FakePlaybackProvider(ProviderId("itunes"), setOf("1"))
        val repository = LibraryRepository(registry(activeId = "ytmusic", listOf(itunesPlayback)))

        val source = repository.resolveSource(track("1", "itunes"))

        assertEquals(AudioSource.Remote("https://example.invalid/itunes/1"), source)
        assertEquals(1, itunesPlayback.resolvedTracks.size)
    }

    // 2. Local track resolves through the Local playback provider.
    @Test
    fun `local track routes to the local playback provider`() = runTest {
        val localPlayback = FakePlaybackProvider(ProviderId("local"), setOf("7"))
        val itunesPlayback = FakePlaybackProvider(ProviderId("itunes"), setOf("7"))
        val repository = LibraryRepository(registry(activeId = "itunes", listOf(localPlayback, itunesPlayback)))

        val source = repository.resolveSource(track("7", "local"))

        assertEquals(AudioSource.Remote("https://example.invalid/local/7"), source)
        assertEquals(1, localPlayback.resolvedTracks.size)
        assertTrue(itunesPlayback.resolvedTracks.isEmpty())
    }

    // 3. Default/test track still resolves through the Default playback provider.
    @Test
    fun `default track routes to the default playback provider`() = runTest {
        val defaultPlayback = FakePlaybackProvider(ProviderId("default"), setOf("local-test"))
        val repository = LibraryRepository(registry(activeId = "itunes", listOf(defaultPlayback)))

        val source = repository.resolveSource(track("local-test", "default"))

        assertEquals(AudioSource.Remote("https://example.invalid/default/local-test"), source)
    }

    // 4. YTM track does not accidentally use whichever provider is active.
    @Test
    fun `ytmusic track never resolves through the active provider's playback capability`() = runTest {
        val itunesPlayback = FakePlaybackProvider(ProviderId("itunes"), setOf("y1"))
        val repository = LibraryRepository(registry(activeId = "itunes", listOf(itunesPlayback)))

        val source = repository.resolveSource(track("y1", "ytmusic"))

        assertNull(source)
        assertTrue(itunesPlayback.resolvedTracks.isEmpty())
    }

    // 5. Unknown providerId degrades gracefully to null.
    @Test
    fun `unknown provider id resolves to null gracefully`() = runTest {
        val itunesPlayback = FakePlaybackProvider(ProviderId("itunes"), setOf("1"))
        val repository = LibraryRepository(registry(activeId = "itunes", listOf(itunesPlayback)))

        assertNull(repository.resolveSource(track("1", "does-not-exist")))
    }

    // 6. Provider switching does not change how an existing track resolves.
    @Test
    fun `switching the active provider does not change how a track resolves`() = runTest {
        val reg = registry(activeId = "itunes", listOf(FakePlaybackProvider(ProviderId("itunes"), setOf("1"))))
        val repository = LibraryRepository(reg)

        val before = repository.resolveSource(track("1", "itunes"))
        reg.activate(ProviderId("ytmusic"))
        val after = repository.resolveSource(track("1", "itunes"))

        assertEquals(before, after)
        assertEquals(AudioSource.Remote("https://example.invalid/itunes/1"), after)
    }

    // 7. A registered provider that cannot resolve still degrades to null.
    @Test
    fun `registered provider that cannot resolve the track yields null`() = runTest {
        val itunesPlayback = FakePlaybackProvider(ProviderId("itunes"), resolvableTrackIds = emptySet())
        val repository = LibraryRepository(registry(activeId = "itunes", listOf(itunesPlayback)))

        assertNull(repository.resolveSource(track("1", "itunes")))
        assertEquals(1, itunesPlayback.resolvedTracks.size)
    }
}
