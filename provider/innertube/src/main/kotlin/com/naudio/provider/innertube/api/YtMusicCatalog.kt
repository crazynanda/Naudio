package com.naudio.provider.innertube.api

import com.naudio.core.model.Track

/**
 * Domain-level results for the YouTube Music browse surface.
 *
 * These are plain Kotlin values, deliberately NOT InnerTube response models:
 * they carry no renderer names, no continuation blobs, no raw JSON, and no
 * reference to an HTTP layer. They are the vocabulary the boundary speaks, and
 * they know nothing about how the backend produced them.
 *
 * Note these are intentionally distinct from `com.naudio.core.model.Artist` and
 * `com.naudio.core.model.Playlist`: those model Naudio's own local, user-curated
 * entities (numeric ids, on-device playlists). A YouTube Music artist or
 * playlist is a remote catalog object with a string browse id and a mutable
 * track list, and folding the two together would corrupt both.
 */

/** One artist page: identity, description and a page of the artist's songs. */
data class YtMusicArtist(
    /** Browse id of the artist channel (e.g. a `UC...` channel id). */
    val id: String,
    val name: String,
    /** Artist bio/subscriber line, when the backend exposes one. */
    val description: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    /** Releases the artist has published, as [YtMusicAlbum] summaries. */
    val albums: List<YtMusicAlbum> = emptyList(),
    /** Opaque cursor for the next page of tracks, or null when exhausted. */
    val continuation: String? = null,
)

/** One album page: identity, release metadata and its ordered track list. */
data class YtMusicAlbum(
    /** Browse id of the album. */
    val id: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    /** Release year as reported by the backend, when available. */
    val year: String? = null,
    val tracks: List<Track> = emptyList(),
    /** Opaque cursor for the next page of tracks, or null when exhausted. */
    val continuation: String? = null,
)

/** One playlist page: identity, author and its ordered track list. */
data class YtMusicPlaylist(
    /** Browse id of the playlist (e.g. a `VLPL...` id). */
    val id: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    /** Total track count as reported by the backend, when available. */
    val trackCount: Int? = null,
    val tracks: List<Track> = emptyList(),
    /** Opaque cursor for the next page of tracks, or null when exhausted. */
    val continuation: String? = null,
)

/**
 * The home feed. [shelves] keeps the backend's own ordering and section
 * headings; callers render shelves in order and may ignore sections whose
 * [YtMusicHomeSection.title] is null.
 */
data class YtMusicHome(
    val shelves: List<YtMusicHomeSection> = emptyList(),
) {
    /** Every track across every shelf, in feed order. */
    val tracks: List<Track> get() = shelves.flatMap { it.tracks }
}

/** One titled group of tracks in the home feed. */
data class YtMusicHomeSection(
    val title: String?,
    val tracks: List<Track> = emptyList(),
)
