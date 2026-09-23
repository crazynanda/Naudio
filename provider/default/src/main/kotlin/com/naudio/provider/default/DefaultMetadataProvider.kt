package com.naudio.provider.default

import com.naudio.core.model.Track
import com.naudio.provider.api.MetadataProvider
import com.naudio.provider.api.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Offline, local-only metadata provider. Deliberately returns no content:
 * online providers are a later milestone. Its only job today is to prove the
 * provider seam end-to-end.
 */
class DefaultMetadataProvider : MetadataProvider {

    private val available = MutableStateFlow(true)

    override val id: ProviderId = ProviderId.Default

    override val displayName: String = "Local library"

    override val isAvailable: Flow<Boolean> = available.asStateFlow()

    override fun search(query: String): Flow<List<Track>> = flowOf(emptyList())

    override suspend fun lookup(trackId: String): Track? = null
}
