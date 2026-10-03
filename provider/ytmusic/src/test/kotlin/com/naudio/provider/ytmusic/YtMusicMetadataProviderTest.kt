package com.naudio.provider.ytmusic

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.core.network.NetworkException
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
import com.naudio.provider.api.ProviderId
import com.naudio.provider.innertube.api.YtMusicBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [YtMusicMetadataProvider] against the [YtMusicBackend] boundary.
 *
 * The provider must be a pure adapter: it owns the provider id, the display
 * name, the blank-query short circuit and the [PageToken] discipline — and
 * nothing else. These tests pin exactly that surface.
 */
class YtMusicMetadataProviderTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `registers under the backend provider id`() {
        val provider = YtMusicMetadataProvider(FakeYtMusicBackend())

        assertEquals(ProviderId("ytmusic"), provider.id)
        assertEquals(YtMusicProviderIds.YTMUSIC, provider.id.value)
        assertEquals("ytmusic", YtMusicBackend.PROVIDER_ID)
        assertEquals(YtMusicBackend.PROVIDER_ID, YtMusicProviderIds.YTMUSIC)
    }

    @Test
    fun `exposes a human readable display name`() {
        assertEquals("YouTube Music", YtMusicMetadataProvider(FakeYtMusicBackend()).displayName)
    }

    // ------------------------------------------------------------------
    // Search delegation
    // ------------------------------------------------------------------

    @Test
    fun `search is delegated to the backend with a null continuation`() = runTest {
        val backend = FakeYtMusicBackend(page = Page(listOf(FakeYtMusicBackend.track("v1")), null))

        val page = YtMusicMetadataProvider(backend).searchTracks("adele")

        assertEquals(listOf("v1"), page.items.map { it.id })
        assertEquals("adele", backend.lastQuery)
        assertNull(backend.lastContinuation)
    }

    @Test
    fun `an opaque token is handed to the backend verbatim`() = runTest {
        val backend = FakeYtMusicBackend(page = Page(emptyList(), PageToken.Opaque("NEXT-2")))

        val page = YtMusicMetadataProvider(backend).searchTracks("q", token = PageToken.Opaque("NEXT-1"))

        assertEquals("NEXT-1", backend.lastContinuation)
        assertEquals(PageToken.Opaque("NEXT-2"), page.nextToken)
    }

    @Test
    fun `an offset token is rejected and never reinterpreted`() = runTest {
        val backend = FakeYtMusicBackend()

        val ex = assertFailsWith<IllegalArgumentException> {
            YtMusicMetadataProvider(backend).searchTracks("q", token = PageToken.Offset(10))
        }

        assertEquals(YtMusicMetadataProvider.TOKEN_TYPE_ERROR, ex.message)
        assertTrue(backend.calls.isEmpty(), "an invalid token must not reach the backend")
    }

    @Test
    fun `a blank query short circuits without reaching the backend`() = runTest {
        val backend = FakeYtMusicBackend(page = Page(listOf(FakeYtMusicBackend.track("v1")), null))

        val page = YtMusicMetadataProvider(backend).searchTracks("   ")

        assertTrue(page.items.isEmpty())
        assertNull(page.nextToken)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `the page returned by the backend is passed through untouched`() = runTest {
        val token = PageToken.Opaque("OPAQUE")
        val backend = FakeYtMusicBackend(page = Page(listOf(FakeYtMusicBackend.track("v1")), token))

        val page = YtMusicMetadataProvider(backend).searchTracks("q")

        assertEquals(token, page.nextToken)
    }

    // ------------------------------------------------------------------
    // lookupTrack
    // ------------------------------------------------------------------

    @Test
    fun `lookupTrack delegates to the backend song operation`() = runTest {
        val expected = Track(
            id = "v1",
            providerId = "ytmusic",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
        )
        val backend = FakeYtMusicBackend(song = expected)

        val track = YtMusicMetadataProvider(backend).lookupTrack("v1")

        assertEquals(expected, track)
        assertEquals(listOf("song"), backend.calls)
    }

    @Test
    fun `lookupTrack reports an unknown id as null`() = runTest {
        val backend = FakeYtMusicBackend(song = null)

        assertNull(YtMusicMetadataProvider(backend).lookupTrack("nope"))
    }

    // ------------------------------------------------------------------
    // Failures pass straight through
    // ------------------------------------------------------------------

    @Test
    fun `a backend failure propagates unchanged`() = runTest {
        val provider = YtMusicMetadataProvider(
            ThrowingYtMusicBackend(NetworkException.Connectivity("offline")),
        )

        val ex = assertFailsWith<NetworkException.Connectivity> { provider.searchTracks("q") }
        assertEquals("offline", ex.message)
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        val provider = YtMusicMetadataProvider(
            ThrowingYtMusicBackend(CancellationException("caller cancelled")),
        )

        assertFailsWith<CancellationException> { provider.searchTracks("q") }
        assertFailsWith<CancellationException> { provider.lookupTrack("v") }
    }

    /** Backend that fails every operation with the same throwable. */
    private class ThrowingYtMusicBackend(private val error: Throwable) :
        com.naudio.provider.innertube.api.YtMusicBackend {
        private fun fail(): Nothing = throw error

        override suspend fun search(query: String, continuation: String?): Page<Track> = fail()
        override suspend fun home() = fail()
        override suspend fun song(videoId: String): Track? = fail()
        override suspend fun artist(browseId: String, continuation: String?) = fail()
        override suspend fun album(browseId: String, continuation: String?) = fail()
        override suspend fun playlist(browseId: String, continuation: String?) = fail()
        override suspend fun related(videoId: String, continuation: String?): Page<Track> = fail()
        override suspend fun resolvePlayback(track: Track): AudioSource? = fail()
    }
}