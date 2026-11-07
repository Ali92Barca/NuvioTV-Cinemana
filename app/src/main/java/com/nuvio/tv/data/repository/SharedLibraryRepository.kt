package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

internal fun libraryIdentityType(type: String) = if (type.lowercase() in setOf("series", "tv", "anime")) "series" else type.lowercase()

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SharedLibraryRepository @Inject constructor(
    private val delegate: LibraryRepositoryImpl,
    private val identities: SharedPlaybackIdentity,
    private val resolver: SharedPlaybackResolver,
    private val local: LibraryPreferences,
    private val profiles: ProfileManager
) : LibraryRepository by delegate {
    override val libraryItems = combine(delegate.libraryItems, identities.entries) { items, _ ->
        items.map { it.copy(id = identities.canonical(it.id, libraryIdentityType(it.type) == "series")) }
            .groupBy { libraryIdentityType(it.type) to it.id }.values.map { group ->
                group.maxBy { it.listedAt }.copy(listKeys = group.flatMap { it.listKeys }.toSet(),
                    listRanks = group.flatMap { it.listRanks.entries }.associate { it.key to it.value })
            }
    }
    override val membershipItemIds = combine(delegate.libraryItems, identities.entries) { items, _ ->
        items.flatMap { item -> identities.related(item.id, libraryIdentityType(item.type) == "series")
            .map { libraryIdentityType(item.type) to it } }.toSet()
    }
    private fun membership(id: String, type: String, watchlist: Boolean): Flow<Boolean> = flow {
        resolver.resolve(id)
        emitAll(combine(identities.entries, sourceMode) { _, mode -> mode }.flatMapLatest { mode ->
            val canonical = identities.canonical(id, libraryIdentityType(type) == "series")
            val ids = if (mode == LibrarySourceMode.LOCAL) identities.related(id, libraryIdentityType(type) == "series") else setOf(canonical)
            val effectiveType = if (identities.related(id).size > 1) libraryIdentityType(type) else type
            combine(ids.map { alias -> if (watchlist) delegate.isInWatchlist(alias, effectiveType) else delegate.isInLibrary(alias, effectiveType) }) { it.any { value -> value } }
        })
    }
    override fun isInLibrary(itemId: String, itemType: String) = membership(itemId, itemType, false)
    override fun isInWatchlist(itemId: String, itemType: String) = membership(itemId, itemType, true)
    private suspend fun canonical(item: LibraryEntryInput): LibraryEntryInput {
        resolver.resolve(item.itemId)
        val id = identities.canonical(item.itemId, libraryIdentityType(item.itemType) == "series")
        val parsed = parseContentIds(id)
        val aliases = identities.related(id, libraryIdentityType(item.itemType) == "series")
        val knownTmdb = aliases.firstNotNullOfOrNull { parseContentIds(it).tmdb }
        return item.copy(itemId = id, itemType = if (aliases.size > 1) libraryIdentityType(item.itemType) else item.itemType,
            imdbId = parsed.imdb ?: item.imdbId, tmdbId = parsed.tmdb ?: item.tmdbId ?: knownTmdb)
    }
    private suspend fun inLocal(item: LibraryEntryInput, profileId: Int): Boolean =
        identities.related(item.itemId, libraryIdentityType(item.itemType) == "series")
            .any { local.containsItem(it, item.itemType, profileId) }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val profile = profiles.activeProfileId.value
        val linked = canonical(item)
        val result = delegate.getMembershipSnapshot(linked)
        return result.copy(listMembership = result.listMembership + (LOCAL_LIBRARY_LIST_KEY to inLocal(linked, profile)))
    }
    override suspend fun toggleDefault(item: LibraryEntryInput, confirmedRemovalProviders: Set<TrackingProviderId>): TrackingMembershipApplyResult {
        val profile = profiles.activeProfileId.value
        val linked = canonical(item)
        check(profile == profiles.activeProfileId.value) { "Profile changed during list update" }
        if (sourceMode.first() == LibrarySourceMode.LOCAL && inLocal(linked, profile)) {
            delegate.removeLocalIdentityLibraryAliases(identities.related(linked.itemId), linked.itemType, profile)
            return TrackingMembershipApplyResult()
        }
        // One canonical mutation; aliases never fan out into tracking-provider writes.
        return delegate.toggleDefault(linked, confirmedRemovalProviders)
    }
    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges,
        confirmedRemovalProviders: Set<TrackingProviderId>): TrackingMembershipApplyResult {
        val profile = profiles.activeProfileId.value
        val linked = canonical(item)
        check(profile == profiles.activeProfileId.value) { "Profile changed during list update" }
        val result = delegate.applyMembershipChanges(linked, changes, confirmedRemovalProviders)
        if (!result.requiresRemovalConfirmation && changes.desiredMembership[LOCAL_LIBRARY_LIST_KEY] == false) {
            delegate.removeLocalIdentityLibraryAliases(identities.related(linked.itemId) - linked.itemId, linked.itemType, profile)
        }
        return result
    }
}
