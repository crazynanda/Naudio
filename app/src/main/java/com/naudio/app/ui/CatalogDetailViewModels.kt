package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.AlbumDetail
import com.naudio.core.model.ArtistDetail
import com.naudio.data.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Load state of a catalog detail page (M21).
 *
 * The four cases mirror the existing [LibraryQueryState] vocabulary — Loading,
 * Results, Error — and add [Empty] for the distinct, non-error outcome of "this
 * catalog entity exists but has no content". Empty is deliberately NOT an Error:
 * an artist with no songs is a valid, displayable page, and conflating the two
 * would tell the user a request failed when it succeeded.
 */
sealed interface CatalogDetailState<out T> {
    /** A fetch is in flight; the screen shows its loading indicator. */
    data object Loading : CatalogDetailState<Nothing>

    /** The entity loaded and carries content. */
    data class Success<T>(val value: T) : CatalogDetailState<T>

    /**
     * The provider has no such entity, or has none registered for it. This is a
     * successful lookup with an empty answer — not a failure.
     */
    data object Empty : CatalogDetailState<Nothing>

    /** The lookup failed (transport, HTTP status, decode). */
    data class Error(val message: String) : CatalogDetailState<Nothing>
}

/** [CatalogDetailState] specialised to an artist, for the screen's own typing. */
typealias ArtistDetailState = CatalogDetailState<ArtistDetail>

/** [CatalogDetailState] specialised to an album. */
typealias AlbumDetailState = CatalogDetailState<AlbumDetail>

/**
 * Loads one artist page (M21).
 *
 * Unidirectional data flow exactly like [PlaylistViewModel]: the screen passes an
 * id, the ViewModel owns the fetch, and the result is observed as a
 * [StateFlow]. It depends on [LibraryRepository] only — no provider type, no
 * catalog id format, and nothing YouTube-specific.
 *
 * Playback is intentionally absent: playing an artist's songs reuses the existing
 * queue mechanism ([PlaybackViewModel.setQueue]), so no second playback path can
 * grow here.
 *
 * Every load cancels the in-flight one, so rapidly switching artists cannot let
 * a slow earlier response overwrite a newer one.
 */
class ArtistViewModel(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ArtistDetailState>(CatalogDetailState.Empty)
    val state: StateFlow<ArtistDetailState> = _state.asStateFlow()

    /** The provider id + catalog id currently open; null when no artist is open. */
    private val openArtist = MutableStateFlow<Pair<String, String>?>(null)
    private var loadJob: Job? = null

    /**
     * Intent: open the artist identified by [providerId]/[artistId].
     *
     * A blank id is rejected without a request — it can only be a caller mistake,
     * and [LibraryRepository] would answer null for it anyway.
     */
    fun openArtist(providerId: String, artistId: String) {
        if (artistId.isBlank()) {
            _state.value = CatalogDetailState.Empty
            openArtist.value = null
            return
        }
        openArtist.value = providerId to artistId
        loadJob?.cancel()
        // Loading is published SYNCHRONOUSLY, before the coroutine is dispatched:
        // setting it inside the coroutine would leave the previous state on
        // screen for a frame, so a newly opened artist would flash the previous
        // page's "isn't available" message before the spinner appeared.
        _state.value = CatalogDetailState.Loading
        loadJob = viewModelScope.launch {
            _state.value = try {
                val artist = libraryRepository.artist(providerId, artistId)
                if (artist == null) CatalogDetailState.Empty else CatalogDetailState.Success(artist)
            } catch (cancellation: CancellationException) {
                // Cancellation is not a failure the user should ever see: it
                // means a newer load superseded this one, and that newer load
                // owns the state from here (it published its own Loading
                // synchronously). Leaving the state untouched is what stops a
                // cancelled request from stranding the screen on a spinner, and
                // from being misreported as a failure.
                throw cancellation
            } catch (error: Exception) {
                CatalogDetailState.Error(error.message ?: "Couldn't load this artist")
            }
        }
    }

    /** Intent: close the artist screen (Back), clearing its state. */
    fun closeArtist() {
        loadJob?.cancel()
        openArtist.value = null
        _state.value = CatalogDetailState.Empty
    }

    /** Intent: retry the currently open artist after an error. */
    fun retry() {
        openArtist.value?.let { (providerId, artistId) -> openArtist(providerId, artistId) }
    }
}

/**
 * Loads one album page (M21). Same contract as [ArtistViewModel]; see it for the
 * state vocabulary, cancellation discipline and the deliberate absence of any
 * playback logic.
 *
 * The returned [AlbumDetail.tracks] order is the release order and is preserved
 * all the way into the queue, so this ViewModel deliberately does not sort,
 * filter or reorder them.
 */
class AlbumViewModel(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AlbumDetailState>(CatalogDetailState.Empty)
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    private val openAlbum = MutableStateFlow<Pair<String, String>?>(null)
    private var loadJob: Job? = null

    /** Intent: open the album identified by [providerId]/[albumId]. */
    fun openAlbum(providerId: String, albumId: String) {
        if (albumId.isBlank()) {
            _state.value = CatalogDetailState.Empty
            openAlbum.value = null
            return
        }
        openAlbum.value = providerId to albumId
        loadJob?.cancel()
        // Published synchronously for the same reason as [openArtist]: no frame
        // may show the previous page's state while this one loads.
        _state.value = CatalogDetailState.Loading
        loadJob = viewModelScope.launch {
            _state.value = try {
                val album = libraryRepository.album(providerId, albumId)
                if (album == null) CatalogDetailState.Empty else CatalogDetailState.Success(album)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                CatalogDetailState.Error(error.message ?: "Couldn't load this album")
            }
        }
    }

    /** Intent: close the album screen (Back), clearing its state. */
    fun closeAlbum() {
        loadJob?.cancel()
        openAlbum.value = null
        _state.value = CatalogDetailState.Empty
    }

    /** Intent: retry the currently open album after an error. */
    fun retry() {
        openAlbum.value?.let { (providerId, albumId) -> openAlbum(providerId, albumId) }
    }
}