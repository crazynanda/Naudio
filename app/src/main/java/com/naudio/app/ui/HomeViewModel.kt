package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.Track
import com.naudio.data.repository.LibraryQueryState
import com.naudio.data.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Immutable UI state for the home screen. */
data class HomeUiState(
    val query: String = "",
    val providerName: String? = null,
    val searchState: LibraryQueryState = LibraryQueryState.Idle,
) {
    val results: List<Track>
        get() = (searchState as? LibraryQueryState.Results)?.tracks.orEmpty()
}

/**
 * Unidirectional data flow: the screen sends intents ([onQueryChange], [onRetry]),
 * the ViewModel owns state, the screen renders state. No player, no network.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Bumped on [onRetry] to re-drive the search pipeline (e.g. after an error). */
    private val retrySignal = MutableStateFlow(0)

    private val search = combine(query.debounce(300), retrySignal) { q, _ -> q }
        .flatMapLatest { q -> repository.search(flowOf(q)) }

    val uiState: StateFlow<HomeUiState> = combine(
        query,
        repository.activeProviderName(),
        search,
    ) { q, providerName, searchState ->
        HomeUiState(
            query = q,
            providerName = providerName,
            searchState = searchState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /** Intent: the user edited the search query. */
    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** Intent: retry after an error (re-subscribes the search stream). */
    fun onRetry() {
        retrySignal.update { it + 1 }
    }
}
