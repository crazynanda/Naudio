package com.naudio.provider.local

import com.naudio.core.model.AudioSource
import com.naudio.core.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LocalPlaybackProviderTest {

    private val provider = LocalPlaybackProvider()

    @Test
    fun `resolves a local track to its MediaStore content uri`() = runTest {
        val track = Track(id = "42", providerId = "local", title = "Song", artist = "Artist")

        val source = provider.resolve(track)

        assertIs<AudioSource.Local>(source)
        assertEquals("content://media/external/audio/media/42", source.uri)
    }

    @Test
    fun `rejects tracks from other providers`() = runTest {
        val track = Track(id = "1440913503", providerId = "itunes", title = "Song", artist = "Artist")

        assertNull(provider.resolve(track))
    }

    @Test
    fun `rejects non-numeric ids`() = runTest {
        val track = Track(id = "not-a-number", providerId = "local", title = "Song", artist = "Artist")

        assertNull(provider.resolve(track))
    }

    @Test
    fun `provider id matches the metadata provider id`() = runTest {
        assertEquals(LocalProviderIds.LOCAL, provider.id.value)
    }
}
