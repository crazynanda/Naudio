package com.naudio.core.model

/**
 * The category used to filter a catalog search (M23).
 *
 * A search text query plus a category is the only input the data layer needs:
 * the Text query selects the provider's song page, and the category then
 * overlays Songs/Artists/Albums as typed, deduplicated views of that page.
 *
 * This is a presentation/filter value, deliberately kept out of the provider
 * surface — no transport concept belongs in the domain boundary, and the
 * metadata provider's `searchTracks` remains a songs-only call.
 */
enum class SearchCategory(val label: String) {
    ALL("All"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
}

/** The set of all search categories (for dropdowns / chips). */