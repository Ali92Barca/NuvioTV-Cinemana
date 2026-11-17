package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.repository.friendsActivity
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FriendActivityDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun HomeViewModel.refreshTraktFriendsWhileVisible() {
    try {
        profileManager.activeProfileId.flatMapLatest { profile ->
            traktSettingsDataStore.observeShowFriendsOnHome(profile)
                .combine(traktAuthService.socialAuthState) { enabled, auth ->
                    Triple(profile, enabled && auth.isAuthenticated, auth.userSlug ?: auth.username)
                }
        }.distinctUntilChanged().collectLatest { (profileId, authenticated, account) ->
            traktFriends.value = null
            if (!authenticated) return@collectLatest
            while (true) {
                val context = appContext.withAppLocale()
                traktAuthService.friendsActivity()?.let { activities ->
                    val currentAuth = traktAuthService.getCurrentAuthState()
                    if (profileManager.activeProfileId.first() != profileId || !currentAuth.isAuthenticated ||
                        (currentAuth.userSlug ?: currentAuth.username) != account) return@collectLatest
                    val cards = activities.map { activity ->
                        MetaPreview(
                            id = activity.id,
                            type = if (activity.series) ContentType.SERIES else ContentType.MOVIE,
                            name = activity.title,
                            poster = activity.poster,
                            rawPosterUrl = activity.poster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = activity.backdrop, logo = null, description = null,
                            releaseInfo = null, imdbRating = null, genres = emptyList(),
                            imdbId = activity.id.takeIf { it.startsWith("tt") },
                            tmdbId = activity.id.removePrefix("tmdb:").takeIf { activity.id.startsWith("tmdb:") },
                            friendActivity = FriendActivityDisplay(
                                activity.friend, activity.watching, activity.episode, activity.avatar, activity.timestamp
                            )
                        )
                    }
                    traktFriends.value = cards.takeIf { it.isNotEmpty() }?.let {
                        CatalogRow("org.nuvio.trakt", "Trakt", "", "friends",
                            context.getString(R.string.trakt_friends_home), ContentType.MOVIE,
                            items = it, hasMore = false)
                    }
                }
                delay(90_000)
            }
        }
    } finally {
        traktFriends.value = null
    }
}

internal fun CatalogRow.toFriendsGridItems(): List<GridItem> = buildList {
    add(GridItem.SectionDivider(catalogName, catalogId, addonBaseUrl, addonId, type = ""))
    items.forEach { item ->
        add(GridItem.Content(item, addonBaseUrl, catalogId, catalogName, addonId))
    }
}
