package com.naudio.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naudio.core.model.History
import com.naudio.core.model.SearchCategory
import com.naudio.core.model.Track
import com.naudio.data.provider.ProviderRegistry
import com.naudio.data.repository.CatalogQueryState
import com.naudio.data.repository.HistoryRepository
import com.naudio.data.repository.LibraryRepository
import com.naudio.provider.api.ProviderId
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

/** One selectable provider entry for the Home screen selector. */
data class ProviderOption(
    val id: String,
    val displayName: String,
)

/** Immutable UI state for the home screen. */
data class HomeUiState(
    val query: String = "",
    /** The category the chips show as selected. */
    val category: SearchCategory = SearchCategory.ALL,
    val providerName: String? = null,
    val activeProviderId: String? = null,
    val providers: List<ProviderOption> = emptyList(),
    /**
     * The typed, deduplicated catalog results. This is what the screen renders:
     * the whole [results] collection, selected by [category], so nothing is
     * silently dropped and nothing is cast to the wrong element type.
     */
    val searchState: CatalogQueryState = CatalogQueryState.Idle,
    /**
     * M17: the most recent playback-history events, newest first. Read-only
     * projection of the history log — the ViewModel never writes history, and
     * the same list is what Android Auto browses. Empty means the row is
     * hidden entirely.
     */
    val recentlyPlayed: List<History> = emptyList(),
) {

}

/**
 * Unidirectional data flow: the screen sends intents ([onQueryChange], [onRetry]),
 * the ViewModel owns state, the screen renders state. No player, no network.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: LibraryRepository,
    private val registry: ProviderRegistry,
    historyRepository: HistoryRepository,
) : ViewModel() {

    /** The selected search category, matched to the chips on screen. */
    private val category = MutableStateFlow(SearchCategory.ALL)

    /** Bumped on [onRetry] to re-drive the search pipeline (e.g. after an
     *  error); a fresh flatMapLatest subscription re-runs the current query.
     */
    private val retrySignal = MutableStateFlow(0)

    /** The search query. */
    private val query = MutableStateFlow("")

    /** Reactive catalog search: re-subscribes on every query debounce tick or
     * whenever the user taps a category chip. Both inputs are combined up front,
     * so typing a query and selecting a category both drive the very same
     * [repository.searchCatalog] call with the current values. The stream is
     * always halted and restarted with the latest pair instead of queued or
     * leaked.
     */
    private val search = combine(query.debounce(300), category, retrySignal) { q, c, _ -> q to c }
        .flatMapLatest { (q, c) -> repository.searchCatalog(flowOf(q), c) }

    /** Provider identity (name + id), computed separately. */
    private val providerInfo = combine(
        repository.activeProviderName(),
        registry.active,
    ) { providerName, activeProvider ->
        Pair(providerName, activeProvider?.id?.value)
    }

    // M17: reading the history repository is all this ViewModel does with it —
    // the account lives in HistoryTracker and the persistence in Room.
    private val recentlyPlayed = historyRepository.observeRecent()

    /**
     * Merge provider identity with the search pipeline into [HomeUiState].
     * [query] is included directly so the controlled TextField reacts to every
     * keystroke immediately, while [search] preserves the 300 ms debounce for
     * the catalog search itself.
     */
    private val merged = combine(
        providerInfo,
        category,
        query,
        search,
        recentlyPlayed,
    ) { (providerName, activeProviderId), category, currentQuery, searchState, history ->
        HomeUiState(
            query = currentQuery,
            category = category,
            providerName = providerName,
            activeProviderId = activeProviderId,
            providers = registry.all().map { ProviderOption(it.id.value, it.displayName) },
            searchState = searchState,
            recentlyPlayed = history,
        )
    }

    val uiState: StateFlow<HomeUiState> = merged.stateIn(
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

    /** Intent: switch the active provider through the existing registry. */
    fun onSelectProvider(id: String) {
        registry.activate(ProviderId(id))
    }

    /** Intent: the user tapped a category chip. */
    fun setCategory(category: SearchCategory) {
        this.category.value = category
    }

    /** Intent: the user tapped a search category chip (M23). */
    fun onSelectCategory(category: SearchCategory) {
        setCategory(category)
    }
}
