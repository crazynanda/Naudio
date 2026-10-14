package com.naudio.data

import com.naudio.core.model.CatalogAlbumSummary
import com.naudio.core.model.CatalogArtistSummary
import com.naudio.core.model.SearchCategory
import com.naudio.core.model.Track
import com.naudio.data.repository.CatalogSearchResult

/**
 * One sorted batch of catalog search results, exactly as the categorizer
 * produced it and ready for the UI to render section by section.
 *
 * Unlike the sealed types that describe a single section, this list carries the
 * *model* entities the categorizer produced — CatalogAlbumSummary and
 * CatalogArtistSummary — so the screen can render each section in one pass
 * without any type test or cast.
 *
 * Songs display exactly as received; artists and albums wait until the first
 * card that carries a real provider id, so a total failure never silences a
 * valid section.
 */
data class CatalogSearchProjection(
    val songs: List<Track>,
    val artists: List<CatalogArtistSummary>,
    val albums: List<CatalogAlbumSummary>,
) {
    /** True when this projection carries no usable catalog identity anywhere. */
    val isEmpty: Boolean
        get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

/**
 * Pure categorizer for M23 search.
 *
 * Category values are the display labels the chips render; the projections are
 * produced here. Each category returns exactly the sections the label names —
 * Songs shows every track on the page in page order; Artists/Albums show the
 * distinct entities the page reported, deduplicated by provider + catalog id
 * and only for tracks that actually carried one. A section is present only
 * when the provider reported a real identity for it; the display name is never
 * used as an id.
 */
object CatalogSearchCategorizer {

    /**
     * Classify [tracks] into the requested [category], projecting the
     * catalog entities the tracks surface.
     */
    fun categorize(
        tracks: List<Track>,
        category: SearchCategory,
    ): CatalogSearchProjection = when (category) {
        SearchCategory.ALL -> CatalogSearchProjection(
            songs = tracks,
            artists = artistsOf(tracks),
            albums = albumsOf(tracks),
        )
        SearchCategory.SONGS -> CatalogSearchProjection(
            songs = tracks,
            artists = emptyList(),
            albums = emptyList(),
        )
        SearchCategory.ARTISTS -> CatalogSearchProjection(
            songs = emptyList(),
            artists = artistsOf(tracks),
            albums = emptyList(),
        )
        SearchCategory.ALBUMS -> CatalogSearchProjection(
            songs = emptyList(),
            artists = emptyList(),
            albums = albumsOf(tracks),
        )
    }

    private fun artistsOf(tracks: List<Track>): List<CatalogArtistSummary> {
        // Stable order: first-seen artist wins. Depend only on provider-reported
        // ids; null ids contribute nothing.
        val firstByArtist = LinkedHashMap<Pair<String, String>, CatalogArtistSummary>(tracks.size)
        for (track in tracks) {
            track.artistId?.let { artistId ->
                if (artistId.isNotBlank()) {
                    val key = track.providerId to artistId
                    val existing = firstByArtist[key]
                    if (existing == null) {
                        firstByArtist[key] = CatalogArtistSummary(
                            id = artistId,
                            providerId = track.providerId,
                            name = track.artist,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }
            }
        }
        return firstByArtist.values.toList()
    }

    private fun albumsOf(tracks: List<Track>): List<CatalogAlbumSummary> {
        val firstByAlbum = LinkedHashMap<Pair<String, String>, CatalogAlbumSummary>(tracks.size)
        for (track in tracks) {
            track.albumId?.let { albumId ->
                if (albumId.isNotBlank()) {
                    val key = track.providerId to albumId
                    val existing = firstByAlbum[key]
                    if (existing == null) {
                    firstByAlbum[key] = CatalogAlbumSummary(
                        id = albumId,
                        providerId = track.providerId,
                        title = track.album.orEmpty(),
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        year = null,
                    )
                    }
                }
            }
        }
        return firstByAlbum.values.toList()
    }
}
