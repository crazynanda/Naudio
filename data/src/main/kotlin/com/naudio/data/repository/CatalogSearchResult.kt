package com.naudio.data.repository

import com.naudio.core.model.CatalogAlbumSummary
import com.naudio.core.model.CatalogArtistSummary
import com.naudio.core.model.Track

/**
 * The result of a categorized catalog search (M23).
 *
 * Each category is a separate typed projection of the *same* underlying song
 * query (see [CatalogSearchCategorizer]). No category performs its own
 * network call: a category is a view over the provider's song page, and an
 * artist/album card is a track row that happens to carry a provider-reported
 * catalog id.
 */
sealed interface CatalogSearchResult {
    /** Songs: every track on the page, in page order. */
    data class Tracks(val tracks: List<Track>) : CatalogSearchResult

    /** Artists: distinct tracks by [CatalogArtistSummary], deduplicated by
     *  [providerId] + [id]. An entry is present only when the track reported
     *  [artistId] from the provider; it is never invented.
     */
    data class Artists(
        val items: List<CatalogArtistSummary>,
    ) : CatalogSearchResult

    /** Albums: distinct tracks by [CatalogAlbumSummary], deduplicated by
     *  [providerId] + [id]. An entry is present only when the track reported
     *  [albumId] from the provider; it is never invented.
     */
    data class Albums(
        val items: List<CatalogAlbumSummary>,
    ) : CatalogSearchResult
}

/**
 * The UI-facing state of a categorized catalog search (M23). This is a
 * separate state type from [LibraryQueryState] so the existing song-search
 * pipeline is untouched; a search screen switches on the two state types as
 * appropriate.
 */
sealed interface CatalogQueryState {
    data object Idle : CatalogQueryState
    data object Loading : CatalogQueryState
    data class Results(
        val category: com.naudio.core.model.SearchCategory,
        val results: List<CatalogSearchResult>,
    ) : CatalogQueryState
    data class Error(val message: String) : CatalogQueryState
}
