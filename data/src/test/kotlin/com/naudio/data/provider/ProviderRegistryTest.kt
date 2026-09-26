package com.naudio.data.provider

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PlaybackProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {

    private class FakeMetadataProvider(override val id: ProviderId) : MetadataProvider {
        override val displayName: String = id.value
        override suspend fun searchTracks(
            query: String,
            offset: Int,
            limit: Int,
        ): com.naudio.provider.api.Page<com.naudio.core.model.Track> =
            Page(emptyList(), nextOffset = null)
        override suspend fun lookupTrack(id: String): com.naudio.core.model.Track? = null
    }

    @Test
    fun `first registered provider is active initially`() {
        val a = FakeMetadataProvider(ProviderId("a"))
        val b = FakeMetadataProvider(ProviderId("b"))
        val registry = ProviderRegistry(listOf(a, b))

        assertEquals(a, registry.activeProvider)
    }

    @Test
    fun `activate switches to requested provider`() {
        val a = FakeMetadataProvider(ProviderId("a"))
        val b = FakeMetadataProvider(ProviderId("b"))
        val registry = ProviderRegistry(listOf(a, b))

        assertTrue(registry.activate(ProviderId("b")))
        assertEquals(b, registry.activeProvider)
    }

    @Test
    fun `activate with unknown id returns false and keeps current provider`() {
        val a = FakeMetadataProvider(ProviderId("a"))
        val registry = ProviderRegistry(listOf(a))

        assertFalse(registry.activate(ProviderId("nope")))
        assertEquals(a, registry.activeProvider)
    }

    @Test
    fun `empty registry has no active provider`() {
        val registry = ProviderRegistry(emptyList())

        assertNull(registry.activeProvider)
    }

    @Test
    fun `all returns providers in registration order`() {
        val a = FakeMetadataProvider(ProviderId("a"))
        val b = FakeMetadataProvider(ProviderId("b"))
        val registry = ProviderRegistry(listOf(b, a))

        assertEquals(listOf(b, a), registry.all())
    }

    @Test
    fun `playback provider is resolvable by exact id`() {
        val playback = FakePlaybackProvider(ProviderId("a"))
        val registry = ProviderRegistry(listOf(FakeMetadataProvider(ProviderId("a"))), listOf(playback))

        assertEquals(playback, registry.playbackProvider(ProviderId("a")))
    }

    @Test
    fun `playback provider falls back to active provider id`() {
        val playback = FakePlaybackProvider(ProviderId("a"))
        val registry = ProviderRegistry(
            listOf(FakeMetadataProvider(ProviderId("a")), FakeMetadataProvider(ProviderId("b"))),
            listOf(playback),
        )

        assertEquals(playback, registry.playbackProvider(ProviderId("b")))
    }

    @Test
    fun `playback provider unknown id returns null when no active fallback`() {
        val registry = ProviderRegistry(emptyList(), listOf(FakePlaybackProvider(ProviderId("a"))))

        assertNull(registry.playbackProvider(ProviderId("nope")))
    }

    @Test
    fun `allPlayback returns playback providers in registration order`() {
        val p1 = FakePlaybackProvider(ProviderId("a"))
        val p2 = FakePlaybackProvider(ProviderId("b"))
        val registry = ProviderRegistry(emptyList(), listOf(p2, p1))

        assertEquals(listOf(p2, p1), registry.allPlayback())
    }

    private class FakePlaybackProvider(override val id: ProviderId) : PlaybackProvider {
        override suspend fun resolve(track: Track): AudioSource? = null
    }
}
