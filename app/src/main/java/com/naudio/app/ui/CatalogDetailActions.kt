package com.naudio.app.ui

import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.AlbumSummary
import com.naudio.core.model.Track

/**
 * The decisions the catalog detail screens make, as pure values (M21).
 *
 * These live apart from the Composables because they are the parts worth
 * testing: what a tap *means*, not how a row is drawn. The screens call these
 * helpers, so the tests below exercise the same code the UI runs rather than a
 * parallel re-implementation.
 *
 * Nothing here touches a player, a ViewModel or a repository. The only output is
 * a [PlaybackRequest] / [CatalogNavigation], which MainActivity feeds into the
 * EXISTING [PlaybackViewModel.setQueue] and the existing screen switch. That is
 * what keeps M21 from growing a second playback mechanism.
 */

/**
 * A queue intent: the tracks to play and where to start.
 *
 * Deliberately just the two values [PlaybackViewModel.setQueue] takes. Building
 * this instead of calling the coordinator directly is what lets a test assert
 * "Play Album starts at 0 and keeps release order" without a player.
 */
internal data class PlaybackRequest(
    val tracks: List<Track>,
    val startIndex: Int,
)

/** A catalog navigation intent: which provider's entity, and which one. */
internal data class CatalogNavigation(
    val providerId: String,
    val catalogId: String,
)

/**
 * Pure decisions behind [ArtistDetailScreen] and [AlbumDetailScreen].
 *
 * `internal` because it is an implementation detail of `:app` alone: the screens
 * and MainActivity are its only callers, and no other module needs it. Kotlin's
 * `internal` is module-scoped (not package-scoped), so the module's unit tests
 * reach it as friend modules while nothing outside `:app` can.
 */
internal object CatalogDetailActions {

    // ---------- playback requests ----------

    /**
     * The request for a "Play top songs" / "Play album" tap, or null when there
     * is nothing to play.
     *
     * Null for an empty list is a real guard, not a formality: the screen also
     * disables the button in that case, and a null here means the two can never
     * disagree. Playback always starts at index 0 so a collection plays in the
     * order the provider supplied — for an album that is release order.
     */
    fun playAll(tracks: List<Track>): PlaybackRequest? =
        if (tracks.isEmpty()) null else PlaybackRequest(tracks = tracks, startIndex = 0)

    /**
     * The request for tapping the track at [index], or null when the index is
     * out of bounds.
     *
     * The WHOLE list is carried alongside [startIndex] — the same shape a user
     * selecting one item of a playlist or an artist page gets — so tapping a
     * track queues its collection rather than playing that track in isolation.
     */
    fun selectTrack(tracks: List<Track>, index: Int): PlaybackRequest? =
        if (index !in tracks.indices) {
            null
        } else {
            PlaybackRequest(tracks = tracks, startIndex = index)
        }

    // ---------- navigation ----------

    /**
     * The navigation for tapping a track's artist label, or null when the track
     * carries no artist catalog id.
     *
     * The same rule as [openAlbum], applied to a [Track]: a label is navigable
     * ONLY when the provider reported that entity's id for that very name
     * ([Track.artistId]). A provider that reports none — the local library, a
     * source with no catalog links, a track rehydrated from a stored copy that
     * predates the id — yields null, so the caller keeps the label plain text
     * instead of inventing an id or issuing a malformed request. The display
     * name is never used as, or turned into, an id.
     */
    fun openArtist(track: Track): CatalogNavigation? {
        val artistId = track.artistId
        if (artistId.isNullOrBlank() || track.providerId.isBlank()) return null
        return CatalogNavigation(providerId = track.providerId, catalogId = artistId)
    }

    /**
     * The navigation for tapping an album card, or null when the card has no
     * usable catalog id.
     *
     * This is the M21 Step 9 rule made executable: a card is navigable ONLY when
     * the provider actually supplied its id. A blank or absent id yields null, so
     * the caller keeps the card non-clickable instead of inventing an id or
     * issuing a malformed provider request. No title is ever used as an id.
     */
    fun openAlbum(summary: AlbumSummary): CatalogNavigation? {
        if (summary.id.isBlank() || summary.providerId.isBlank()) return null
        return CatalogNavigation(providerId = summary.providerId, catalogId = summary.id)
    }

    /** True when a card can be tapped, i.e. [openAlbum] would succeed. */
    fun isAlbumNavigable(summary: AlbumSummary): Boolean = openAlbum(summary) != null

    /** True when a track's artist label can be tapped, i.e. [openArtist] would succeed. */
    fun isArtistNavigable(track: Track): Boolean = openArtist(track) != null

    // ---------- back-navigation from an album to its artist ----------

    /**
     * Context for opening an album that came from an artist page.
     *
     * Store this at open time; back uses it to return to the exact artist that was
     * open, rather than re-deriving anything from the album's (display-only) artist
     * string.
     */
    data class OpenAlbumContext(
        val providerId: String,
        val artistId: String,
    )

    /**
     * The catalog entity this album detail screen should return to on Back.
     *
     * The [OpenAlbumContext] is captured at the moment the album was opened (from
     * the artist's discography row). It is the source of truth for the artist
     * identity, even though [albumDetail.artist] is a display string only — and so
     * is never used as, or derived from, an id.
     *
     * Returns null when the album was opened without an artist context (for now,
     * that only happens for albums that are not opened from an artist page), so the
     * caller keeps the user where they are instead of inventing an identity.
     */
    fun backFromAlbum(
        openAlbumContext: OpenAlbumContext?,
        albumDetail: AlbumDetail,
    ): BackFromAlbumResult? {
        val ctx = openAlbumContext ?: return null
        if (ctx.providerId.isBlank() || ctx.artistId.isBlank()) return null
        return BackFromAlbumResult(ctx.providerId, ctx.artistId)
    }

    /** The artist that back from [albumDetail] (with [openAlbumContext]) returns to. */
    data class BackFromAlbumResult(
        val providerId: String,
        val artistId: String,
    )

    // ---------- album-track artwork fallback ----------

    /**
     * The artwork to show for [track] in the context of [albumDetail].
     *
     * Album tracks usually carry no individual artwork in the response, so a row in
     * an album list falls back to the album artwork. A track that already has its
     * own artwork keeps it, so the fallback is a complement to the track's own value
     * — not a replacement.
     */
    fun albumTrackArtwork(track: Track, albumDetail: AlbumDetail): String? =        // Null OR blank track artwork falls back to the album's artwork.
        track.artworkUrl?.takeIf { it.isNotBlank() } ?: albumDetail.artworkUrl
}
