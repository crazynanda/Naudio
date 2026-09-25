package com.naudio.provider.default

import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Offline, local-only metadata provider. Serves one canned demo track backed
 * by a bundled asset so the player foundation is end-to-end demonstrable:
 * online providers are a later milestone.
 */
class DefaultMetadataProvider : MetadataProvider {

    private val available = MutableStateFlow(true)

    override val id: ProviderId = ProviderId.Default

    override val displayName: String = "Local library"

    override val isAvailable: Flow<Boolean> = available.asStateFlow()

    override fun search(query: String): Flow<List<Track>> =
        flowOf(if (query.isBlank()) emptyList() else listOf(demoTrack))

    override suspend fun lookup(trackId: String): Track? =
        if (trackId == demoTrack.id) demoTrack else null

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
