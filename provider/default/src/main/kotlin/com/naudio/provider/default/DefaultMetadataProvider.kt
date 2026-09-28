package com.naudio.provider.default

import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.Page
import com.naudio.provider.api.PageToken
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
        token: PageToken?,
        limit: Int,
    ): Page<Track> {
        if (query.isBlank()) return Page(emptyList(), nextToken = null)
        // The default provider has one canned result; a continuation token can
        // never be valid here — treat it as a deterministic caller error.
        val offset = when (token) {
            null -> 0
            is PageToken.Offset -> token.value
            is PageToken.Opaque -> throw IllegalArgumentException(TOKEN_TYPE_ERROR)
        }
        val all = listOf(demoTrack)
        val page = all.drop(offset).take(limit)
        val next = offset + page.size
        return Page(page, nextToken = if (page.size < limit) null else PageToken.Offset(next))
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

        /** Error message for an incompatible (non-offset) page token. */
        const val TOKEN_TYPE_ERROR = "Default provider requires PageToken.Offset"
    }
}
