package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Track
import com.naudio.data.repository.FavoritesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable UI state for the Library (favorites) screen. */
data class LibraryUiState(
    val favorites: List<Track> = emptyList(),
) {
    /** True when the user has not favorited anything yet (empty-state hint). */
    val isEmpty: Boolean
        get() = favorites.isEmpty()
}

/**
 * Unidirectional data flow for the Library screen: observes the favorites
 * repository (Room-backed) and exposes favorite-management intents. Depends on
 * the repository abstraction only — no DAO, no database types.
 */
class LibraryViewModel(
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = favoritesRepository.observeFavorites()
        .map { LibraryUiState(favorites = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState(),
        )

    /** Intent: remove [track] from favorites (provider-aware identity). */
    fun onRemoveFavorite(track: Track) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(track, isFavorite = false)
        }
    }
}
