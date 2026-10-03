package com.naudio.provider.ytmusic

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NetworkException
import com.naudio.provider.api.Page
import com.naudio.provider.api.ProviderId
import com.naudio.provider.innertube.api.YtMusicAlbum
import com.naudio.provider.innertube.api.YtMusicArtist
import com.naudio.provider.innertube.api.YtMusicBackend
import com.naudio.provider.innertube.api.YtMusicHome
import com.naudio.provider.innertube.api.YtMusicPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [YtMusicPlaybackProvider] against the [YtMusicBackend] boundary.
 *
 * The provider is an adapter with no logic of its own: it reports whatever the
 * backend decided. The behaviour that actually matters is the honest one — a
 * backend that cannot serve a stream yields `null`, which the app already knows
 * how to surface, and never a fabricated URL.
 */
class YtMusicPlaybackProviderTest {

    private fun track(id: String, providerId: String = "ytmusic") =
        Track(id = id, providerId = providerId, title = "T", artist = "A")

    @Test
    fun `registers under the same id as the metadata provider`() {
        val provider = YtMusicPlaybackProvider(FakeYtMusicBackend())

        assertEquals(ProviderId(YtMusicMetadataProvider.PROVIDER_ID), provider.id)
    }

    @Test
    fun `a playable track resolves to the backend's source`() = runTest {
        val source = AudioSource.Remote("https://direct.example/audio")
        val provider = YtMusicPlaybackProvider(FakeYtMusicBackend(source = source))

        assertEquals(source, provider.resolve(track("v1")))
    }

    @Test
    fun `no playable source resolves to null rather than a fabricated url`() = runTest {
        val backend = FakeYtMusicBackend(source = null)
        val provider = YtMusicPlaybackProvider(backend)

        assertNull(provider.resolve(track("v1")))
        // The backend WAS consulted — "no source" is a real answer, not a skip.
        assertEquals(listOf("resolvePlayback"), backend.calls)
    }

    @Test
    fun `a foreign track produces no source`() = runTest {
        val backend = FakeYtMusicBackend(source = AudioSource.Remote("https://direct.example/audio"))
        val provider = YtMusicPlaybackProvider(backend)

        // Exact routing is enforced at the boundary; the provider adds no second
        // policy of its own, it simply delegates.
        assertNull(provider.resolve(track("1", providerId = "itunes")))
        assertTrue(backend.calls.isEmpty(), "a foreign track must not be looked up")
    }

    @Test
    fun `a transport failure propagates rather than being reported as no source`() = runTest {
        val provider = YtMusicPlaybackProvider(ThrowingBackend(NetworkException.Timeout("read timed out")))

        val ex = assertFailsWith<NetworkException.Timeout> { provider.resolve(track("v1")) }
        assertEquals("read timed out", ex.message)
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        val provider = YtMusicPlaybackProvider(ThrowingBackend(CancellationException("cancelled")))

        assertFailsWith<CancellationException> { provider.resolve(track("v1")) }
    }

    /** Backend that fails every operation with the same throwable. */
    private class ThrowingBackend(private val error: Throwable) : YtMusicBackend {
        private fun fail(): Nothing = throw error

        override suspend fun search(query: String, continuation: String?): Page<Track> = fail()
        override suspend fun home(): YtMusicHome = fail()
        override suspend fun song(videoId: String): Track? = fail()
        override suspend fun artist(browseId: String, continuation: String?): YtMusicArtist = fail()
        override suspend fun album(browseId: String, continuation: String?): YtMusicAlbum = fail()
        override suspend fun playlist(browseId: String, continuation: String?): YtMusicPlaylist = fail()
        override suspend fun related(videoId: String, continuation: String?): Page<Track> = fail()
        override suspend fun resolvePlayback(track: Track): AudioSource? = fail()
    }
}