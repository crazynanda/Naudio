package com.naudio.provider.api

/**
 * Identifier of a content provider (e.g. the local/offline provider today,
 * online providers in future milestones).
 */
@JvmInline
value class ProviderId(val value: String) {
    companion object {
        val Default = ProviderId("default")
    }
}
