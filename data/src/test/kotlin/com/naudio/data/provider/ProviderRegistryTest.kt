package com.naudio.data.provider

import com.naudio.provider.api.MetadataProvider
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
        override val isAvailable: Flow<Boolean> = flowOf(true)
        override fun search(query: String): Flow<List<com.naudio.core.model.Track>> = flowOf(emptyList())
        override suspend fun lookup(trackId: String): com.naudio.core.model.Track? = null
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
}
