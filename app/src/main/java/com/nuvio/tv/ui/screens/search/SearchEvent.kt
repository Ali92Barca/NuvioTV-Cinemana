package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object SubmitSearch : SearchEvent
    /** Moving from the text input to the results confirms the current search (see #2928 review). */
    data object RememberSearchFromTextInput : SearchEvent
    data object ClearRecentSearches : SearchEvent
    data class RemoveRecentSearch(val query: String) : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data class SelectDiscoverType(val type: String) : SearchEvent
    data class SelectDiscoverCatalog(val catalogKey: String) : SearchEvent
    data class SelectDiscoverYear(val year: String?) : SearchEvent
    data class SelectDiscoverSort(val sort: String?) : SearchEvent
    data class SelectDiscoverLanguage(val languageId: String?) : SearchEvent
    data object RefreshCinemanaLanguages : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data object LoadNextDiscoverResults : SearchEvent

    data object Retry : SearchEvent
}
