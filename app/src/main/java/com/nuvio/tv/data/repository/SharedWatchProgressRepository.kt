package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Reads old native histories alongside canonical histories; never migrates or deletes them on discovery. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SharedWatchProgressRepository @Inject constructor(
    private val delegate: WatchProgressRepositoryImpl,
    private val identities: SharedPlaybackIdentity,
    private val resolver: SharedPlaybackResolver,
    private val profiles: ProfileManager
) : WatchProgressRepository by delegate {
    private fun localNativeIdentity(id: String) = nativePlaybackId(id) || externalMovieId(id) || id.startsWith("xt_s_")
    private val identityChanges = combine(identities.entries, identities.externalRevision) { rows, _ -> rows }
    private fun <T> observe(id: String, block: (Set<String>) -> Flow<T>): Flow<T> = flow {
        resolver.resolve(id)
        emitAll(identityChanges.flatMapLatest { block(identities.related(id).flatMap { identities.externalRelated(it) }
            .flatMap { identities.related(it) }.toSet()) })
    }.flowOn(Dispatchers.Default)
    private fun list(source: Flow<List<WatchProgress>>) = combine(source, identityChanges) { items, _ -> identities.project(items) }.flowOn(Dispatchers.Default)
    private fun latest(items: List<WatchProgress>) = identities.project(items).groupBy { it.contentId }
        .values.mapNotNull { group -> group.maxWithOrNull(compareBy<WatchProgress> { it.lastWatched }.thenBy { it.season ?: 0 }.thenBy { it.episode ?: 0 }) }
        .sortedByDescending { it.lastWatched }
    override val allProgress = combine(delegate.allProgress, identityChanges) { items, _ -> latest(items) }.flowOn(Dispatchers.Default)
    override val continueWatching = combine(delegate.continueWatching, allProgress, identityChanges) { items, all, _ ->
        val newest = all.associateBy { it.contentId }
        latest(items).filter { candidate -> (newest[candidate.contentId]?.lastWatched ?: 0L) <= candidate.lastWatched }
    }.flowOn(Dispatchers.Default)
    override val watchedItems = combine(delegate.watchedItems, identityChanges) { items, _ ->
        // Resolve title aliases once per work, rather than once per watched episode.
        val targets = mutableMapOf<Pair<String, Boolean>, Set<String>>()
        items.flatMap { item ->
            val series = item.season != null || item.contentType in setOf("series", "tv", "anime")
            targets.getOrPut(item.contentId to series) {
                identities.related(item.contentId, seriesHint = true.takeIf { series }).flatMap { identities.externalRelated(it) }
                .flatMap { identities.related(it, seriesHint = true.takeIf { series }) }.toSet()
            }
                .mapNotNull { target -> identities.watched(item, target).takeIf { it.contentId == target } }
        }
    }.flowOn(Dispatchers.Default)
    override fun getProgress(contentId: String) = profiles.activeProfileId.flatMapLatest { getProgress(contentId, it) }
    override fun getProgress(contentId: String, profileId: Int): Flow<WatchProgress?> = observe(contentId) { ids ->
        val legacy = if (ids.size > 1) ids.filter(::localNativeIdentity).map { delegate.localIdentityProgress(it, null, null, profileId) } else emptyList()
        val partHistory = if (ids.any { identities.hasPartBinding(it) }) ids.map { id ->
            delegate.getAllEpisodeProgress(id, profileId).map { episodes -> episodes.values.map { identities.progress(it, contentId) }
                .filter { it.contentId == contentId }.maxByOrNull { it.lastWatched } }
        } else emptyList()
        combine(ids.map { delegate.getProgress(it, profileId) } + legacy + partHistory) { values ->
            values.filterNotNull().map { identities.progress(it, contentId) }
                .filter { it.contentId == contentId }.maxByOrNull { it.lastWatched }
        }
    }
    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int) =
        profiles.activeProfileId.flatMapLatest { getEpisodeProgress(contentId, season, episode, it) }
    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int, profileId: Int): Flow<WatchProgress?> = observe(contentId) { ids ->
        val legacy = if (ids.size > 1) ids.filter(::localNativeIdentity).mapNotNull { target ->
            val query = identities.externalQuery(contentId, target, season, episode)
            if (target.startsWith("xt_") && query == null) null
            else delegate.localIdentityProgress(target, query?.first ?: season, query?.second ?: episode, profileId)
        } else emptyList()
        combine(ids.mapNotNull { target ->
            val query = identities.externalQuery(contentId, target, season, episode)
            if (target != contentId && (contentId.startsWith("xt_") || target.startsWith("xt_") || identities.hasPartBinding(contentId) || identities.hasPartBinding(target)) && query == null) null
            else delegate.getEpisodeProgress(target, query?.first ?: season, query?.second ?: episode, profileId)
        } + legacy) { values ->
            values.filterNotNull().map { identities.progress(it, contentId) }
                .filter { it.contentId == contentId }.maxByOrNull { it.lastWatched }
        }
    }
    override fun getAllEpisodeProgress(contentId: String) = profiles.activeProfileId.flatMapLatest { getAllEpisodeProgress(contentId, it) }
    override fun getAllEpisodeProgress(contentId: String, profileId: Int): Flow<Map<Pair<Int, Int>, WatchProgress>> = observe(contentId) { ids ->
        val legacy = if (ids.size > 1) ids.filter(::localNativeIdentity).map { delegate.localIdentityEpisodes(it, profileId) } else emptyList()
        combine(ids.map { delegate.getAllEpisodeProgress(it, profileId) } + legacy) { maps ->
            maps.flatMap { it.values }.map { identities.progress(it, contentId) }
                .mapNotNull { item -> item.season?.let { s -> item.episode?.let { e -> (s to e) to item } } }
                .filter { it.second.contentId == contentId }.groupBy { it.first }.mapValues { (_, entries) ->
                entries.maxBy { it.second.lastWatched }.second
            }
        }
    }
    override fun observeWatchedMovieIds() = combine(delegate.observeWatchedMovieIds(), allProgress, identityChanges) { ids, progress, _ ->
        val replaying = progress.filter { it.season == null && !it.isCompleted() && (it.position > 0 || (it.progressPercent ?: 0f) > 0) }.map { it.contentId }.toSet()
        identities.expand(ids).filterNot { identities.canonical(it) in replaying }.toSet()
    }.flowOn(Dispatchers.Default)
    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> = observe(contentId) { ids ->
        val allowed = ids.filter { identities.canProjectEpisode(contentId, it, season, episode) }
        val watched = combine(allowed.mapNotNull { target ->
            val query = if (season != null && episode != null) identities.externalQuery(contentId, target, season, episode) else null
            if (target != contentId && (contentId.startsWith("xt_") || target.startsWith("xt_") || identities.hasPartBinding(contentId) || identities.hasPartBinding(target)) && query == null) null
            else delegate.isWatched(target, if (target == contentId) videoId else null, query?.first ?: season, query?.second ?: episode)
        }) { it.any { value -> value } }
        val progress = if (season != null && episode != null) getEpisodeProgress(contentId, season, episode) else getProgress(contentId)
        combine(watched, progress) { marked, latest ->
            if (latest != null && !latest.isCompleted() && (latest.position > 0 || (latest.progressPercent ?: 0f) > 0)) false
            else marked || latest?.isCompleted() == true
        }.distinctUntilChanged()
    }
    private suspend fun canonical(progress: WatchProgress): WatchProgress {
        kotlinx.coroutines.withTimeoutOrNull(1_500) { resolver.resolve(progress.contentId) }
        return withContext(Dispatchers.Default) { identities.progress(progress) }
    }
    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) = saveProgress(progress, profiles.activeProfileId.value, syncRemote)
    override suspend fun saveProgress(progress: WatchProgress, profileId: Int, syncRemote: Boolean) = delegate.saveProgress(canonical(progress), profileId, syncRemote)
    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        val profile = profiles.activeProfileId.value
        val items = identities.project(progressList.map { canonical(it) })
        if (profiles.activeProfileId.value == profile) delegate.saveProgressBatch(items, syncRemote)
    }
    override suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean) = markAsCompleted(progress, profiles.activeProfileId.value, broadcastTrackingHistory)
    override suspend fun markAsCompleted(progress: WatchProgress, profileId: Int, broadcastTrackingHistory: Boolean) = delegate.markAsCompleted(canonical(progress), profileId, broadcastTrackingHistory)
    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        val profile = profiles.activeProfileId.value
        for (id in progressList.map { it.contentId }.distinct()) {
            kotlinx.coroutines.withTimeoutOrNull(1_500) { resolver.resolve(id) }
        }
        val items = identities.project(progressList)
        if (profiles.activeProfileId.value == profile) delegate.markAsCompletedBatch(items)
    }
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profile = profiles.activeProfileId.value
        resolver.resolve(contentId)
        if (profiles.activeProfileId.value != profile) return
        val aliases = episodeAliases(contentId, season, episode)
        val canonical = identities.canonical(contentId).takeIf { it in aliases } ?: contentId
        delegate.removeProgress(canonical, season, episode)
        (aliases - canonical).forEach { delegate.removeLocalIdentityAlias(it, season, episode, false, profile) }
    }
    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) {
        val profile = profiles.activeProfileId.value
        resolver.resolve(contentId)
        if (profiles.activeProfileId.value != profile) return
        val aliases = episodeAliases(contentId, season, episode)
        val canonical = identities.canonical(contentId).takeIf { it in aliases } ?: contentId
        delegate.removeFromHistory(canonical, if (canonical == contentId) videoId else null, season, episode)
        (aliases - canonical).forEach { delegate.removeLocalIdentityAlias(it, season, episode, true, profile) }
    }
    override suspend fun removeFromHistoryBatch(contentId: String, videoId: String?, episodes: List<Triple<Int, Int, String?>>) {
        val profile = profiles.activeProfileId.value
        resolver.resolve(contentId)
        if (profiles.activeProfileId.value != profile) return
        val canonical = identities.canonical(contentId)
        val unique = episodes.distinctBy { it.first to it.second }
        val byTarget = unique.groupBy { (s, e, _) ->
            canonical.takeIf { it in episodeAliases(contentId, s, e) } ?: contentId
        }
        byTarget.forEach { (target, items) ->
            val mapped = items.map { (s, e, id) -> Triple(s, e, if (target == contentId) id else "$target:$s:$e") }
            delegate.removeFromHistoryBatch(target, videoId.takeIf { target == contentId }, mapped)
            for ((s, e, _) in items) for (alias in episodeAliases(contentId, s, e) - target) {
                delegate.removeLocalIdentityAlias(alias, s, e, true, profile)
            }
        }
    }

    private fun episodeAliases(id: String, season: Int?, episode: Int?): Set<String> {
        val row = identities.entries.value.firstOrNull { it.native == id }
        if (row?.partial == true && (season == null || episode == null)) return setOf(id)
        return identities.related(id).filterTo(mutableSetOf()) { identities.canProjectEpisode(id, it, season, episode) }
    }
    override fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean) = delegate.applyOptimisticWatchedMovie(identities.expand(ids), add)
    override fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean) = delegate.revertOptimisticWatchedMovie(identities.expand(ids), add)
    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val result = mutableMapOf<String, Set<Pair<Int, Int>>>()
        delegate.getWatchedShowEpisodes().forEach { (id, episodes) ->
            identities.related(id).flatMap { identities.externalRelated(it) }.flatMap { identities.related(it) }.toSet().forEach { alias ->
                val compatible = episodes.mapNotNull { (s, e) ->
                    val mapped = identities.watched(WatchedItem(id, "series", "", s, e, 0), alias)
                    if (mapped.contentId == alias && mapped.season != null && mapped.episode != null) mapped.season to mapped.episode else null
                }.toSet()
                result[alias] = result[alias].orEmpty() + compatible
            }
        }
        return result
    }
    override suspend fun getShowIdSiblings(): Map<String, Set<String>> {
        val result = delegate.getShowIdSiblings().toMutableMap()
        identities.entries.value.filter { it.series }.forEach { row -> identities.related(row.native).forEach { result[it] = result[it].orEmpty() + identities.related(it) } }
        return result
    }
    override fun getAiredEpisodeOrder(contentId: String) = observe(contentId) { delegate.getAiredEpisodeOrder(identities.canonical(contentId)) }
    override fun observeNextUpSeeds() = combine(delegate.observeNextUpSeeds(), identityChanges) { items, _ -> latest(items) }.flowOn(Dispatchers.Default)
    override fun isDroppedShow(contentId: String) = delegate.isDroppedShow(identities.canonical(contentId))
    override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        normalizeParentContentId(parentContentId, videoId, profiles.activeProfileId.value)
    override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?, profileId: Int): String {
        val resolved = resolver.resolve(parentContentId)
        val row = identities.entries.value.firstOrNull { it.native == parentContentId }
        if (row?.partial == true && videoId !in row.episodes.values) return parentContentId
        return delegate.normalizeParentContentId(resolved, if (resolved == parentContentId) videoId else null, profileId)
    }
    override suspend fun prepareNextUpSeed(progress: WatchProgress) = delegate.prepareNextUpSeed(canonical(progress))
    override fun observeOptimisticContinueWatchingUpdates() = delegate.observeOptimisticContinueWatchingUpdates().map { identities.progress(it) }.flowOn(Dispatchers.Default)
}
