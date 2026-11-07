package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.catalogRowLegacyKey
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.DiscoverSelectionDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val discoverSelectionDataStore: DiscoverSelectionDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val serverCatalog: ServerCatalog,
    private val cinemanaAvailabilityMonitor: com.nuvio.tv.core.network.CinemanaAvailabilityMonitor,
    @ApplicationContext private val context: Context,
    private val searchSeriesAvailability: com.nuvio.tv.data.cee.SearchSeriesAvailability? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    var savedRowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()

    /**
     * Load-more jobs, tracked separately from [activeSearchJobs] because that list is replaced
     * wholesale by each search run. Cancelled alongside it in [cancelSearchRun].
     */
    private val activeLoadMoreJobs: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var searchRunJob: Job? = null
    private var activeSearchQuery: String? = null
    private var searchGeneration = 0L
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var availabilityJob: Job? = null
    private val hiddenUnavailableCinemana = mutableSetOf<String>()
    private var suggestionJob: Job? = null
    private var liveSearchJob: Job? = null
    private var lastRequestKey: String? = null
    private var lastCompletedRequestKey: String? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    private var revealBatchAfterNextDiscoverFetch = false
    private var ceeConsecutiveDuplicatePages = 0
    private var hideUnreleasedContent = false

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
        const val SUGGESTION_DEBOUNCE_MS = 150L

        /**
         * Live search fires while typing, but each run fans out to every enabled addon catalog, so
         * it waits longer than the suggestion debounce to avoid a request storm per keystroke.
         */
        const val LIVE_SEARCH_DEBOUNCE_MS = 350L

        const val MAX_SUGGESTIONS = 8
        /** Splits titles and queries into words. */
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        const val MAX_RECENT_SEARCHES = 8
    }

    init {
        posterOptions.bind(viewModelScope)

        viewModelScope.launch {
            var previousUnsupported =
                cinemanaAvailabilityMonitor.availability.value ==
                    com.nuvio.tv.core.network.CinemanaAvailability.Unsupported

            cinemanaAvailabilityMonitor.availability
                .collect { availability ->
                    val unsupported =
                        availability == com.nuvio.tv.core.network.CinemanaAvailability.Unsupported

                    if (unsupported != previousUnsupported) {
                        previousUnsupported = unsupported
                        val currentQuery = _uiState.value.query
                        if (currentQuery.isNotBlank()) {
                            cancelSearchRun()
                            lastRequestKey = null
                            lastCompletedRequestKey = null
                            performSearch(currentQuery, rememberToHistory = false)
                        } else {
                            if (discoverVisible) refreshDiscoverForNetworkChange()
                            else discoverRefreshPending = true
                        }
                    }
                }
        }
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    discoverJob?.cancel()
                    discoverJob = null
                    revealBatchAfterNextDiscoverFetch = false
                    _uiState.update {
                        it.copy(
                            discoverInitialized = false,
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverCatalogs = emptyList(),
                            selectedDiscoverType = "movie",
                            selectedDiscoverCatalogKey = null,
                            selectedDiscoverGenre = null,
                            selectedDiscoverYear = null,
                            selectedDiscoverSort = null,
                            discoverResults = emptyList(),
                            pendingDiscoverResults = emptyList(),
                            discoverHasMore = true,
                            discoverPage = 1
                        )
                    }
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    private var discoverVisible = false
    private var discoverRefreshPending = false

    internal fun setDiscoverVisible(visible: Boolean) {
        discoverVisible = visible
        if (!visible) {
            metaPrefetchJob?.cancel()
            return
        }
        if (discoverRefreshPending) {
            discoverRefreshPending = false
            viewModelScope.launch { refreshDiscoverForNetworkChange() }
        }
    }

    fun ensureDiscoverLoaded() {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverInitialized || state.discoverLoading) return
        viewModelScope.launch { loadDiscoverCatalogs() }
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaPrefetchedIds.add(id)
            metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
        }
    }

    /**
     * Returns the cached backdrop URL from a previously prefetched meta, or null.
     */
    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.RememberSearchFromTextInput -> rememberSearchFromTextInput()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.RemoveRecentSearch -> removeRecentSearch(event.query)
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverYear -> selectDiscoverExtra("year", event.year)
            is SearchEvent.SelectDiscoverSort -> selectDiscoverExtra("sort", event.sort)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            is SearchEvent.SelectDiscoverLanguage -> selectDiscoverLanguage(event.languageId)
            SearchEvent.RefreshCinemanaLanguages -> refreshCinemanaLanguages()
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            SearchEvent.Retry -> {
                // An explicit retry must refetch even though nothing about the request changed.
                lastRequestKey = null
                lastCompletedRequestKey = null
                cancelSearchRun()
                performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
            }
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            // Narrow the strip to what still matches before anything is fetched, so a letter
            // that rules a title out drops it on that keystroke rather than a fetch later.
            // Narrowing does not clear the strip when every current title stops matching. The
            // fetch stays authoritative for an empty result.
            val narrowed = rankedSuggestions(it.suggestions, trimmedInput.lowercase())
            it.copy(
                query = query,
                suggestions = if (narrowed.isEmpty()) it.suggestions else narrowed,
                error = null,
                isSearching = false,
                // Keep whatever is on screen while a keystroke waits to run. Clearing here flashed
                // the no-results state on every letter, because on a remote each letter outlasts the
                // debounce. The screen renders skeleton rows for this window instead.
                catalogRows = if (trimmedInput.length < MIN_SEARCH_QUERY_LENGTH) emptyList() else it.catalogRows
            )
        }

        // Drop in-flight requests for the previous keystroke before scheduling the next run.
        cancelSearchRun()

        // Live search: results follow what you type, like mobile. Debounced because each run hits
        // every enabled addon catalog.
        liveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
            liveSearchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(LIVE_SEARCH_DEBOUNCE_MS)
                performSearch(query, keepSuggestions = true)
            }
        } else {
            // Emptying the field has to retire the submitted query too. Leaving it set kept the
            // screen in its results state with nothing to show, instead of falling back to recent
            // searches, until the screen was rebuilt by navigating away and back.
            performSearch(query)
        }

        fetchSuggestions(trimmed)
    }

    /** Match rank for [title], lower being better, or null when it does not match [queryLower]. */
    private fun suggestionRank(title: String, queryLower: String): Int? {
        val titleLower = title.lowercase()
        if (titleLower == queryLower) return 0
        if (titleLower.startsWith(queryLower)) return 1
        if (titleLower.contains(queryLower)) return 2

        // Allow multi-word matches such as "wolf wall" -> "The Wolf of Wall Street".
        // Each query word must consume a different title word.
        val queryWords = queryLower.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (queryWords.size < 2) return null
        val unmatchedTitleWords = titleLower.split(WORD_SEPARATOR).filterTo(mutableListOf()) { it.isNotEmpty() }
        val everyWordMatches = queryWords.all { word ->
            val index = unmatchedTitleWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) unmatchedTitleWords.removeAt(index)
            index >= 0
        }
        return if (everyWordMatches) 3 else null
    }

    /**
     * The strip contents for [names], best match first, capped at [MAX_SUGGESTIONS].
     *
     * Titles of the same rank keep the order of [names], which is the addon's own relevance order.
     * Sorting them alphabetically put five titles ahead of "Jurassic Park" for "juras", and of
     * "Slow Horses" for "slo", past the few completions a TV keyboard shows.
     */
    private fun rankedSuggestions(names: Collection<String>, queryLower: String): List<String> =
        names
            .mapNotNull { name -> suggestionRank(name, queryLower)?.let { name to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(MAX_SUGGESTIONS)

    /**
     * Every catalog's titles, taken one position at a time: each catalog's first title, then each
     * one's second, and so on, in catalog order. Each catalog's best matches lead, and the result
     * does not depend on which catalog happened to answer first.
     */
    private fun mergedCatalogNames(namesByCatalog: Map<Int, List<String>>, catalogCount: Int): List<String> {
        val catalogs = (0 until catalogCount).mapNotNull { namesByCatalog[it] }
        val merged = LinkedHashSet<String>()
        for (position in 0 until (catalogs.maxOfOrNull { it.size } ?: 0)) {
            catalogs.forEach { names -> names.getOrNull(position)?.let(merged::add) }
        }
        return merged.toList()
    }

    private fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        // Already searched, so a fetch would repeat itself and the strip is current. Leave it
        // standing: live search submits as the user types, so typing a space between words
        // trims back to the submitted query and lands here mid-query.
        if (query == _uiState.value.submittedQuery.trim() && _uiState.value.catalogRows.isNotEmpty()) {
            return
        }

        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

            val addons = try {
                searchAddons()
            } catch (_: Exception) {
                return@launch
            }

            val allTargets = buildSearchTargets(addons)
            val firstAddonId = allTargets.firstOrNull()?.first?.id
            val searchTargets = if (firstAddonId != null) allTargets.filter { it.first.id == firstAddonId } else emptyList()
            if (searchTargets.isEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
                return@launch
            }

            // Each catalog's titles in the order it returned them, keyed by its place in searchTargets.
            val namesByCatalog = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
            val queryLower = query.lowercase()
            val suggestionJobs = searchTargets.mapIndexed { catalogIndex, (addon, catalog) ->
                launch {
                    try {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = 100,
                            extraArgs = mapOf("search" to query),
                            supportsSkip = false,
                            posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
                        ).collect { result ->
                            if (result is NetworkResult.Success && _uiState.value.query.trim() == query) {
                                val names = result.data.items.map { it.name }
                                val previous = namesByCatalog.put(catalogIndex, names)
                                // Catalog results arrive independently and are merged, so a batch
                                // whose titles all fail the filter ranks to nothing while the batch
                                // holding the match is still in flight. Only the settle below may
                                // empty the strip: an empty push tells the keyboard there are no
                                // completions, and it does not always take them back when the next
                                // batch lands.
                                if (names != previous) {
                                    val ranked = rankedSuggestions(
                                        mergedCatalogNames(namesByCatalog, searchTargets.size),
                                        queryLower
                                    )
                                    if (ranked.isNotEmpty()) {
                                        _uiState.update { it.copy(suggestions = ranked) }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // The settle below treats joinAll() as "collection is over".
                        throw e
                    } catch (_: Exception) {
                        // Ignore per-catalog errors for suggestions
                    }
                }
            }

            suggestionJobs.joinAll()

            // Every catalog job has completed, so this is the first point the query is known
            // to have no suggestions. Until here the strip keeps the previous query's titles
            // rather than blinking on every keystroke. It must be cleared here or it would go
            // on captioning text the field no longer contains.
            if (_uiState.value.query.trim() == query) {
                val ranked = rankedSuggestions(mergedCatalogNames(namesByCatalog, searchTargets.size), queryLower)
                _uiState.update { it.copy(suggestions = ranked) }
            }
        }
    }

    private fun submitSearch() {
        // An explicit submit just skips the remaining debounce; the live run would land anyway.
        liveSearchJob?.cancel()
        performSearch(_uiState.value.query, rememberToHistory = true)
    }

    /**
     * Moving from the text input into the results confirms the current query the same way
     * Done/submit does. Live search may already have results on screen without an explicit
     * submit; saving here
     * avoids losing useful history while still not recording every keystroke prefix.
     */
    private fun rememberSearchFromTextInput() {
        val state = _uiState.value
        val query = state.submittedQuery.trim().ifBlank { state.query.trim() }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return
        val hasRealResults = state.catalogRows.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        } || catalogsMap.values.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        }
        if (!hasRealResults) return
        viewModelScope.launch {
            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
        }
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryDataStore.removeRecentSearch(query)
        }
    }

    private fun resetCatalogAccumulator() {
        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0
        hiddenUnavailableCinemana.clear()
    }

    private fun cancelSearchRun() {
        searchGeneration++
        availabilityJob?.cancel()
        availabilityJob = null
        searchRunJob?.cancel()
        searchRunJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        activeLoadMoreJobs.forEach { it.cancel() }
        activeLoadMoreJobs.clear()
        activeSearchQuery = null
    }

    /**
     * Identifies a search by everything that changes what it would return: the query, the released
     * filter, and the exact set of catalogs it would hit. Enabling an addon or flipping the filter
     * changes the key, so those still refetch.
     */
    private fun buildRequestKey(
        query: String,
        searchTargets: List<Pair<Addon, CatalogDescriptor>>
    ): String = buildString {
        append(query.lowercase())
        append('|')
        append(hideUnreleasedContent)
        append('|')
        append(
            searchTargets.joinToString(separator = "|") { (addon, catalog) ->
                "${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
            }
        )
    }


    /**
     * @param keepSuggestions live search runs this on every keystroke, while the field is still
     * being typed into and the suggestion strip is the whole point. Those runs leave the strip
     * alone. A submit or a retry replaces the screen with results, which retires it.
     */
    private fun performSearch(
        rawQuery: String,
        rememberToHistory: Boolean = false,
        keepSuggestions: Boolean = false
    ) {
        val query = rawQuery.trim()
        if (!keepSuggestions) {
            suggestionJob?.cancel()
        }
        _uiState.update {
            it.copy(
                submittedQuery = submittedSearchQuery(query),
                query = rawQuery,
                suggestions = if (keepSuggestions) it.suggestions else emptyList()
            )
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            cancelSearchRun()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()
            lastRequestKey = null
            lastCompletedRequestKey = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            ensureDiscoverLoaded()
            return
        }

        // Submit can immediately follow the debounced live-search launch. Reuse an active run for
        // the same query, but cancel a different query's entire scope before starting this one.
        if (activeSearchQuery == query && searchRunJob?.isActive == true) return
        cancelSearchRun()
        val generation = searchGeneration
        activeSearchQuery = query

        val job = viewModelScope.launch {
            val addons = try {
                searchAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration && activeSearchQuery == query) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: context.getString(com.nuvio.tv.R.string.search_error_load_addons_failed)) }
                }
                return@launch
            }

            if (generation != searchGeneration || activeSearchQuery != query) return@launch

            val searchTargets = buildSearchTargets(addons)

            // Same query against the same catalogs, and that run either finished or is still
            // arriving, so there is nothing new to fetch. Without this, pressing Done after live
            // search had already run the query tore the rows down and refetched everything, and
            // deleting a letter then retyping it did the same. A run that was cancelled part way
            // is deliberately not counted, so it gets to finish rather than staying half filled.
            val requestKey = buildRequestKey(query, searchTargets)
            val alreadySatisfied = requestKey == lastRequestKey &&
                (requestKey == lastCompletedRequestKey || activeSearchJobs.any { it.isActive })
            if (alreadySatisfied) {
                // An explicit submit that lands on a query the live search already finished still
                // counts as a search the user confirmed, so remember it instead of skipping out
                // of the history save below.
                if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                    viewModelScope.launch {
                        searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                    }
                }
                return@launch
            }
            lastRequestKey = requestKey

            // Committed to a new run: drop the previous query's work and accumulated rows.
            activeSearchJobs.forEach { it.cancel() }
            activeSearchJobs = emptyList()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()

            // Rows are left alone here. Clearing them produced an empty frame between the old
            // results and the placeholders below, which is the flash this screen used to show.
            _uiState.update { it.copy(isSearching = true, error = null, installedAddons = addons) }

            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = context.getString(R.string.search_error_no_catalogs),
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "__placeholder_${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = PLACEHOLDER_IMAGE_URL,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            // Only shimmer when there is nothing real to look at. If the previous query's results
            // are still up, they stay until this query's results replace them, so refining a search
            // is a single swap rather than results -> shimmer -> results on every letter.
            _uiState.update { state ->
                val showingRealRows = state.catalogRows.any { row ->
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
                }
                if (showingRealRows) state else state.copy(catalogRows = placeholderRows)
            }

            val jobs = searchTargets.map { (addon, catalog) ->
                launch {
                    loadCatalog(addon, catalog, query, generation)
                }
            }
            pendingCatalogResponses = jobs.size
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            try {
                jobs.joinAll()
            } finally {
                if (
                    generation == searchGeneration &&
                    activeSearchQuery == query &&
                    uiState.value.submittedQuery.trim() == query
                ) {
                    lastCompletedRequestKey = requestKey
                    _uiState.update { it.copy(isSearching = false) }
                    // Only explicit submit (or moving into results from the text input — see
                    // rememberSearchFromTextInput)
                    // writes history. Live search fires per keystroke and would otherwise record
                    // every prefix ("do", "dog") even when the user never confirmed the search.
                    if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                        viewModelScope.launch {
                            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                        }
                    }
                }
            }
        }
        searchRunJob = job
    }

    private suspend fun loadCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String,
        generation: Long
    ) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip,
            posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        addonBaseUrl = addon.baseUrl,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    catalogsMap[key] = result.data
                    scheduleAvailabilityCheck(generation, query)
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate()
                }
                is NetworkResult.Error -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update { it.copy(error = result.message ?: context.getString(com.nuvio.tv.R.string.search_error_failed)) }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun isCurrentSearch(generation: Long, query: String): Boolean =
        generation == searchGeneration && uiState.value.submittedQuery.trim() == query

    /**
     * Pages one row of the current search. The page belongs to the search run that created it:
     * it is keyed on [SearchUiState.submittedQuery] rather than the live field, and every
     * assignment back into [catalogsMap] is gated on that run still being current, so a late page
     * cannot merge into a different query's rows.
     */
    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val (key, currentRow) = catalogsMap.entries.firstOrNull { (_, row) ->
            row.addonId == addonId && row.apiType == type && row.catalogId == catalogId
        }?.let { it.key to it.value } ?: return

        if (currentRow.isLoading || !currentRow.hasMore) {
            return
        }

        val generation = searchGeneration
        val query = uiState.value.submittedQuery.trim()
        if (query.isBlank()) {
            return
        }

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val job = viewModelScope.launch {
            val addon = uiState.value.installedAddons.find { it.id == addonId && it.baseUrl == currentRow.addonBaseUrl }
                ?: uiState.value.installedAddons.find { it.id == addonId } ?: run {
                clearRowLoading(key, generation, query)
                return@launch
            }

            val nextSkip = currentRow.nextCatalogSkip()
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        val latestRow = catalogsMap[key] ?: return@collect
                        catalogsMap[key] = latestRow.mergeCatalogPage(result.data)
                        scheduleAvailabilityCheck(generation, query)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        clearRowLoading(key, generation, query)
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
        activeLoadMoreJobs.add(job)
        job.invokeOnCompletion { activeLoadMoreJobs.remove(job) }
    }

    /** Drops the loading flag for a row, but only while its search run is still current. */
    private fun clearRowLoading(key: String, generation: Long, query: String) {
        if (!isCurrentSearch(generation, query)) return
        val row = catalogsMap[key] ?: return
        if (!row.isLoading) return
        catalogsMap[key] = row.copy(isLoading = false)
        scheduleCatalogRowsUpdate()
    }

    private fun scheduleAvailabilityCheck(generation: Long, query: String) {
        val checker = searchSeriesAvailability ?: return
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            delay(750)
            if (!isCurrentSearch(generation, query)) return@launch
            val rows = catalogsMap.values.toList()
            val cinemana = rows.filter { it.addonId == "org.nuvio.cinemana.builtin" }
                .flatMap { it.items }.filter { it.id.startsWith("cinemana-series:") }
            val cee = rows.filter { it.addonId == "org.nuvio.cee" }
                .flatMap { it.items }.filter { it.id.startsWith("cee:") }
            for (item in cinemana.distinctBy { it.id }) {
                if (item.id in hiddenUnavailableCinemana) continue
                val matches = cee.filter { checker.sameSeries(item, it) }.distinctBy { it.id }
                if (matches.size != 1 || !checker.shouldHide(item, matches.single())) continue
                if (!isCurrentSearch(generation, query)) return@launch
                // Only hide while the verified alternate is still part of this search.
                if (catalogsMap.values.none { row -> row.addonId == "org.nuvio.cee" &&
                        row.items.any { it.id == matches.single().id } }) continue
                hiddenUnavailableCinemana.add(item.id)
                scheduleCatalogRowsUpdate()
            }
        }
    }

    private fun scheduleCatalogRowsUpdate() {
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.map { key ->
                catalogsMap[key]
                    ?: state.catalogRows.find {
                        it.stableKey() == key
                    }
            }.filterNotNull().filter { row ->
                // Keep placeholder rows (shimmer) and rows with real items.
                // Drop rows that came back empty from the API.
                val isPlaceholder = row.isLoading &&
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                isPlaceholder || row.items.isNotEmpty()
            }
            val availableRows = orderedRows.map { row ->
                if (row.addonId == "org.nuvio.cinemana.builtin") {
                    row.copy(items = row.items.filterNot { it.id in hiddenUnavailableCinemana })
                } else row
            }.filter { it.items.isNotEmpty() || it.isLoading }
            val filteredRows = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                availableRows.map { row ->
                    if (row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true) {
                        row
                    } else {
                        row.filterReleasedItems(today)
                    }
                }
            } else {
                availableRows
            }
            state.copy(
                catalogRows = filteredRows
            )
        }
    }

    private suspend fun searchAddons(): List<Addon> {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons() +
            serverCatalog.searchAddons.first()

        if (cinemanaAvailabilityMonitor.availability.value !=
            com.nuvio.tv.core.network.CinemanaAvailability.Unsupported
        ) {
            return addons
        }

        return addons.filterNot { addon ->
            addon.baseUrl.trimEnd('/').equals(
                com.nuvio.tv.data.cinemana.CinemanaAddonApi.BASE_URL,
                ignoreCase = true
            )
        }
    }

    private suspend fun refreshDiscoverForNetworkChange() {
        val previous = _uiState.value
        if (!previous.discoverInitialized ||
            previous.discoverLocation == DiscoverLocation.OFF
        ) return

        discoverRequestVersion++
        discoverJob?.cancel()
        discoverJob = null

        loadDiscoverCatalogs(fetchContent = false)

        val current = _uiState.value
        val sameCatalog = current.discoverCatalogs.any {
            it.key == previous.selectedDiscoverCatalogKey
        }
        if (sameCatalog) {
            _uiState.update {
                it.copy(
                    selectedDiscoverType = previous.selectedDiscoverType,
                    selectedDiscoverCatalogKey = previous.selectedDiscoverCatalogKey,
                    selectedDiscoverGenre = previous.selectedDiscoverGenre,
                    selectedDiscoverYear = previous.selectedDiscoverYear,
                    selectedDiscoverSort = previous.selectedDiscoverSort,
                    selectedDiscoverLanguage = previous.selectedDiscoverLanguage
                )
            }
        }
        fetchDiscoverContent(reset = true)
    }

    private suspend fun loadDiscoverCatalogs(fetchContent: Boolean = true) {
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            searchAddons()
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.flatMap { addon ->
            val isCinemana = addon.baseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)

            val available = addon.catalogs.filter { catalog ->
                !(catalog.supportsExtra("search") &&
                    catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }) &&
                !(addon.baseUrl.contains("cee.nuvio.invalid", ignoreCase = true) &&
                    (catalog.id in setOf("cee-movies", "cee-series") || catalog.id.startsWith("cee-home-")))
            }

            if (isCinemana) {
                available.groupBy { it.apiType }.mapNotNull { (type, catalogs) ->
                    val categories = catalogs.filter { it.id.startsWith("cinemana_") }
                    val first = categories.firstOrNull { it.id !in setOf("cinemana_all", "cinemana_all_series") } ?: return@mapNotNull null

                    DiscoverCatalog(
                        key = catalogRowLegacyKey(addon.id, type, "cinemana-discover"),
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = first.id,
                        catalogName = "Cinemana",
                        type = type,
                        genres = (listOf(if (type == "series") "cinemana_all_series" else "cinemana_all") + categories.map { it.id }).distinct(),
                        yearOptions = (1950..2027).reversed().map { it.toString() },
                        supportsSkip = first.supportsExtra("skip"),
                        skipStep = 12
                    )
                }
            } else {
                available.map { catalog ->
                    DiscoverCatalog(
                        key = catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id),
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = catalog.extra
                            .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                            ?.options.orEmpty(),
                        yearOptions = catalog.extra
                            .firstOrNull { it.name.equals("year", ignoreCase = true) }
                            ?.options.orEmpty(),
                        sortOptions = catalog.extra
                            .firstOrNull { it.name.equals("sort", ignoreCase = true) }
                            ?.options.orEmpty(),
                        supportsSkip = catalog.supportsExtra("skip"),
                        skipStep = if (addon.baseUrl.contains("cee.nuvio.invalid", ignoreCase = true) && catalog.id in setOf("cee-discover-movies", "cee-discover-series")) 12 else catalog.skipStep()
                    )
                }
            }
        }
        val selectedCatalog = resolveDiscoverCatalog(
            catalogs = discoverCatalogs,
            preferredKey = discoverSelectionDataStore.getSelectedCatalogKey(),
            currentKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedType = selectedCatalog?.type ?: "movie"
        val selectedGenre: String? = if (selectedCatalog?.addonBaseUrl?.contains("cinemana.nuvio.invalid", ignoreCase = true) == true) selectedCatalog.genres.firstOrNull() else null

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true,
                discoverPage = 1
            )
        }
        selectedCatalog?.let { catalog ->
            viewModelScope.launch {
                discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
            }
        }
        if (fetchContent) fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = if (selectedCatalog?.addonBaseUrl?.contains("cinemana.nuvio.invalid", ignoreCase = true) == true) selectedCatalog.genres.firstOrNull() else null
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                selectedDiscoverYear = null,
                selectedDiscoverSort = null,
                selectedDiscoverLanguage = null,
                cinemanaLanguages = emptyList(),
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        selectedCatalog?.let { catalog ->
            viewModelScope.launch {
                discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
            }
        }
        refreshCinemanaLanguages()
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = if (catalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)) catalog.genres.firstOrNull() else null,
                selectedDiscoverYear = null,
                selectedDiscoverSort = null,
                selectedDiscoverLanguage = null,
                cinemanaLanguages = emptyList(),
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        viewModelScope.launch {
            discoverSelectionDataStore.setSelectedCatalogKey(catalog.key)
        }
        refreshCinemanaLanguages()
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverExtra(name: String, value: String?) {
        val state = _uiState.value
        val catalog = state.discoverCatalogs.firstOrNull {
            it.key == state.selectedDiscoverCatalogKey
        } ?: return
        val options = when (name) {
            "year" -> catalog.yearOptions
            "sort" -> catalog.sortOptions
            else -> return
        }
        val isCinemanaSort = name == "sort" &&
            catalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)
        val cinemanaSortKeys = setOf(
            "desc", "asc", "r_desc", "r_asc",
            "stars_desc", "stars_asc",
            "views_desc", "views_asc",
            "rating_desc", "rating_asc",
            "title_asc", "title_desc"
        )
        if (value != null && value !in options &&
            !(isCinemanaSort && value in cinemanaSortKeys)
        ) return

        _uiState.update {
            when (name) {
                "year" -> {
                    val builtIn = catalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true) ||
                        catalog.addonBaseUrl.contains("cee.nuvio.invalid", ignoreCase = true)
                    it.copy(
                        selectedDiscoverYear = value,
                        selectedDiscoverSort = if (builtIn && value != null) null else it.selectedDiscoverSort,
                        selectedDiscoverLanguage = if (builtIn && value != null) null else it.selectedDiscoverLanguage
                    )
                }
                "sort" -> it.copy(selectedDiscoverSort = value)
                else -> it
            }
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverLanguage(languageId: String?) {
        val state = _uiState.value
        val catalog = state.discoverCatalogs.firstOrNull {
            it.key == state.selectedDiscoverCatalogKey
        } ?: return

        if (!catalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)) return

        val allowed = languageId == null ||
            state.cinemanaLanguages.any { it.id == languageId }

        if (!allowed) return
        if (state.selectedDiscoverLanguage == languageId) return

        _uiState.update {
            it.copy(selectedDiscoverLanguage = languageId)
        }
        fetchDiscoverContent(reset = true)
    }

    private var cinemanaLanguageJob: Job? = null
    private val cinemanaLanguageCache =
        mutableMapOf<String, List<CinemanaLanguageOption>>()

    private fun refreshCinemanaLanguages() {
        val state = _uiState.value
        val catalog = state.discoverCatalogs.firstOrNull {
            it.key == state.selectedDiscoverCatalogKey
        } ?: return

        if (!catalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)) {
            return
        }

        val genre = state.selectedDiscoverGenre ?: return
        val category = com.nuvio.android.cinemana.CinemanaCatalogs.category(genre)
            ?: return

        val categoryIds = category.remoteIds
        val cacheKey = categoryIds

        cinemanaLanguageJob?.cancel()

        _uiState.update {
            it.copy(
                cinemanaLanguages = cinemanaLanguageCache[cacheKey]
                    ?: emptyList(),
                cinemanaLanguagesLoading = true
            )
        }

        cinemanaLanguageJob = viewModelScope.launch {
            try {
                val languages = com.nuvio.android.cinemana.CinemanaClient()
                    .categoryLanguages(categoryIds)
                    .map {
                        CinemanaLanguageOption(
                            id = it.id,
                            name = it.name,
                            movieCount = it.movieCount,
                            seriesCount = it.seriesCount
                        )
                    }

                cinemanaLanguageCache[cacheKey] = languages

                val current = _uiState.value
                val currentCategory =
                    current.selectedDiscoverGenre?.let {
                        com.nuvio.android.cinemana.CinemanaCatalogs.category(it)
                    }

                if (currentCategory?.remoteIds == categoryIds &&
                    current.selectedDiscoverCatalogKey == state.selectedDiscoverCatalogKey
                ) {
                    val selectedBeforeUpdate = _uiState.value.selectedDiscoverLanguage

                    _uiState.update { latest ->
                        val selected = latest.selectedDiscoverLanguage
                        val availableLanguages = languages.filter { language ->
                            if (latest.selectedDiscoverType == "series") {
                                language.seriesCount > 0
                            } else {
                                language.movieCount > 0
                            }
                        }
                        val stillAvailable = availableLanguages.any { it.id == selected }
                        val defaultLanguage = availableLanguages.firstOrNull { it.id == "7" }

                        latest.copy(
                            selectedDiscoverLanguage = if (latest.selectedDiscoverYear != null) null else if (stillAvailable) selected else defaultLanguage?.id,
                            cinemanaLanguages = languages,
                            cinemanaLanguagesLoading = false
                        )
                    }

                    if (selectedBeforeUpdate != _uiState.value.selectedDiscoverLanguage) {
                        fetchDiscoverContent(reset = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val current = _uiState.value
                val currentCategory =
                    current.selectedDiscoverGenre?.let {
                        com.nuvio.android.cinemana.CinemanaCatalogs.category(it)
                    }

                if (currentCategory?.remoteIds == categoryIds) {
                    _uiState.update {
                        it.copy(
                            cinemanaLanguages = cinemanaLanguageCache[cacheKey]
                                ?: emptyList(),
                            cinemanaLanguagesLoading = false
                        )
                    }
                }
            }
        }
    }
    private fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                selectedDiscoverYear = null,
                selectedDiscoverLanguage = null,
                cinemanaLanguages = emptyList(),
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        refreshCinemanaLanguages()
        fetchDiscoverContent(reset = true)
    }

    private fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (state.query.trim().isNotEmpty()) return
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private var discoverRequestVersion = 0L

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverRequestVersion++
        val requestVersion = discoverRequestVersion
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                ceeConsecutiveDuplicatePages = 0
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverPage = 1,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            val waitingForCinemanaLanguage =
                selectedCatalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true) &&
                    state.selectedDiscoverGenre != null &&
                    state.selectedDiscoverYear == null &&
                    state.cinemanaLanguagesLoading

            if (waitingForCinemanaLanguage) return@launch

            val currentPage = if (reset) 1 else state.discoverPage + 1
            val visibleCountBeforeRequest = state.discoverResults.size
            val isCinemanaDiscover = selectedCatalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true)
            val effectiveCatalogId = if (isCinemanaDiscover) {
                state.selectedDiscoverGenre ?: selectedCatalog.catalogId
            } else {
                selectedCatalog.catalogId
            }
            val effectiveSkipStep = when {
                isCinemanaDiscover && state.selectedDiscoverYear != null -> 12
                isCinemanaDiscover &&
                    effectiveCatalogId !in setOf("cinemana_all", "cinemana_all_series") -> 30
                else -> selectedCatalog.skipStep
            }
            val skip = if (currentPage <= 1) 0 else (currentPage - 1) * effectiveSkipStep
            val extraArgs = buildMap<String, String> {
                if (!isCinemanaDiscover) {
                    state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
                }
                if (isCinemanaDiscover && state.selectedDiscoverYear == null) {
                    state.selectedDiscoverLanguage
                        ?.takeIf { languageId ->
                            state.cinemanaLanguages.any { it.id == languageId }
                        }
                        ?.let { put("language", it) }
                }
                state.selectedDiscoverYear
                    ?.takeIf { it in selectedCatalog.yearOptions }
                    ?.let { put("year", it) }
                state.selectedDiscoverSort
                    ?.takeIf {
                        state.selectedDiscoverYear == null ||
                            (!selectedCatalog.addonBaseUrl.contains("cinemana.nuvio.invalid", ignoreCase = true) &&
                                !selectedCatalog.addonBaseUrl.contains("cee.nuvio.invalid", ignoreCase = true))
                    }
                    ?.takeIf { sort ->
                    sort in selectedCatalog.sortOptions ||
                        (isCinemanaDiscover && sort in setOf(
                            "desc", "asc", "r_desc", "r_asc",
                            "stars_desc", "stars_asc",
                            "views_desc", "views_asc",
                            "rating_desc", "rating_asc",
                            "title_asc", "title_desc"
                        ))
                }?.let { put("sort", it) }
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = effectiveCatalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                skipStep = effectiveSkipStep,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                if (requestVersion != discoverRequestVersion) return@collect
                if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val rawDeduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val deduped = if (hideUnreleasedContent) {
                            val today = LocalDate.now()
                            rawDeduped.filterNot { it.isUnreleased(today) }
                        } else {
                            rawDeduped
                        }
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val isCeeDiscover = selectedCatalog.addonBaseUrl.contains("cee.nuvio.invalid", ignoreCase = true)
                        if (isCeeDiscover) {
                            ceeConsecutiveDuplicatePages =
                                if (!reset && incoming.isNotEmpty() && !hasNewUniqueIncoming)
                                    ceeConsecutiveDuplicatePages + 1
                                else 0
                        }
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming &&
                            (!isCeeDiscover || incoming.isEmpty() || ceeConsecutiveDuplicatePages >= 2)
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverPage = if (shouldStopPagination) it.discoverPage else currentPage
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> catalog.isSearchable() }
                .map { catalog -> addon to catalog }
        }

        return allSearchTargets
    }

    /**
     * A catalog is only searchable if a search is all it needs. One that also requires something we
     * cannot supply, a mandatory genre for instance, answers with an error or nothing at all, so
     * querying it just costs a request per keystroke and leaves a row that never fills in.
     */
    private fun CatalogDescriptor.isSearchable(): Boolean =
        supportsExtra("search") &&
            extra.none { property ->
                property.isRequired && !property.name.equals("search", ignoreCase = true)
            }

    private fun catalogKey(addonId: String, addonBaseUrl: String, type: String, catalogId: String): String {
        return catalogRowStableKey(addonId, addonBaseUrl, type, catalogId)
    }
}

internal fun resolveDiscoverCatalog(
    catalogs: List<DiscoverCatalog>,
    preferredKey: String?,
    currentKey: String?
): DiscoverCatalog? =
    catalogs.firstOrNull { it.key == preferredKey }
        ?: catalogs.firstOrNull { it.key == currentKey }
        ?: catalogs.firstOrNull()
