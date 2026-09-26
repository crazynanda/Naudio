package com.naudio.provider.default

import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.ProviderId

/**
 * Offline, local-only metadata provider. Serves one canned demo track backed
 * by a bundled asset so the player foundation is end-to-end demonstrable.
 */
class DefaultMetadataProvider : MetadataProvider {

    override val id: ProviderId = ProviderId.Default

    override val displayName: String = "Local library"

    override suspend fun searchTracks(
        query: String,
        offset: Int,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextOffset = null)
        val all = listOf(demoTrack)
        val page = all.drop(offset).take(limit)
        val next = offset + page.size
        return Page(page, nextOffset = if (page.size < limit) null else next)
    }

    override suspend fun lookupTrack(id: String): Track? =
        if (id == demoTrack.id) demoTrack else null

    private companion object {
        val demoTrack = Track(
            id = DefaultPlaybackProvider.TEST_TRACK_ID,
            providerId = "local",
            title = "Test Tone",
            artist = "Naudio Test",
            durationMs = TEST_TONE_DURATION_MS,
        )

        /** Matches the generated asset: 3 s of 440 Hz sine at 8 kHz mono. */
        const val TEST_TONE_DURATION_MS = 3_000L
    }
}
