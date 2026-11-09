package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private data class PendingProgressUpsert(
    val key: String,
    val progress: WatchProgress
)

private data class PendingWatchedUpsert(
    val key: WatchedMutationKey,
    val item: WatchedItem
)

private data class ProgressDeleteVersion(val key: String, val revision: Long)
private data class WatchedDeleteVersion(val key: WatchedMutationKey, val revision: Long)

@Singleton
class WatchStateMutationStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "watch_state_pending_mutations"
    }

    private val gson = Gson()
    private val progressUpsertsKey = stringSetPreferencesKey("progress_upserts")
    private val progressDeletesKey = stringSetPreferencesKey("progress_deletes")
    private val watchedUpsertsKey = stringSetPreferencesKey("watched_upserts")
    private val watchedDeletesKey = stringSetPreferencesKey("watched_deletes")

    private val deleteRevisionKey = longPreferencesKey("delete_revision")
    private val progressDeleteVersionsKey = stringSetPreferencesKey("progress_delete_versions")
    private val watchedDeleteVersionsKey = stringSetPreferencesKey("watched_delete_versions")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun queueProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        val entries = entries.filterValues { !ServerItemRef.isServerId(it.contentId) }
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                pending[key] = progress
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - entries.keys
            preferences[progressDeleteVersionsKey] = (progressVersions(preferences[progressDeleteVersionsKey]) - entries.keys)
                .map { (key, revision) -> gson.toJson(ProgressDeleteVersion(key, revision)) }.toSet()
        }
    }

    suspend fun queueProgressDeletes(keys: Collection<String>, profileId: Int) {
        val normalized = keys.map(String::trim).filter { it.isNotEmpty() && !ServerItemRef.isServerId(it) }.toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]) - normalized
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() + normalized
            val revision = (preferences[deleteRevisionKey] ?: 0L) + 1L
            preferences[deleteRevisionKey] = revision
            preferences[progressDeleteVersionsKey] = (progressVersions(preferences[progressDeleteVersionsKey]) + normalized.associateWith { revision })
                .map { (key, value) -> gson.toJson(ProgressDeleteVersion(key, value)) }.toSet()
        }
    }

    suspend fun pendingProgressUpserts(profileId: Int): Map<String, WatchProgress> {
        val preferences = store(profileId).data.first()
        return parseProgressUpserts(preferences[progressUpsertsKey])
    }

    suspend fun pendingProgressDeletes(profileId: Int): Set<String> {
        return store(profileId).data.first()[progressDeletesKey].orEmpty()
    }

    suspend fun acknowledgeProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                if (pending[key] == progress) pending.remove(key)
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
        }
    }

    suspend fun progressDeleteVersions(keys: Collection<String>, profileId: Int): Map<String, Long> {
        val versions = progressVersions(store(profileId).data.first()[progressDeleteVersionsKey])
        return keys.associateWith { versions[it] ?: 0L }
    }

    suspend fun acknowledgeProgressDeletes(keys: Collection<String>, profileId: Int,
        expectedVersions: Map<String, Long>? = null) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            val versions = progressVersions(preferences[progressDeleteVersionsKey])
            val confirmed = keys.filter { expectedVersions == null || (versions[it] ?: 0L) == expectedVersions[it] }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - confirmed
            preferences[progressDeleteVersionsKey] = (versions - confirmed)
                .map { (key, value) -> gson.toJson(ProgressDeleteVersion(key, value)) }.toSet()
        }
    }

    suspend fun queueWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        val items = items.filterNot { ServerItemRef.isServerId(it.contentId) }
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                pending[item.mutationKey()] = item
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            val upsertKeys = items.mapTo(mutableSetOf(), WatchedItem::mutationKey)
            preferences[watchedDeleteVersionsKey] = (watchedVersions(preferences[watchedDeleteVersionsKey]) - upsertKeys)
                .map { (key, value) -> gson.toJson(WatchedDeleteVersion(key, value)) }.toSet()
            preferences[watchedDeletesKey] = parseWatchedDeletes(preferences[watchedDeletesKey])
                .minus(upsertKeys)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun queueWatchedDeletes(keys: Collection<WatchedMutationKey>, profileId: Int) {
        val normalized = keys.filterNot { ServerItemRef.isServerId(it.contentId) }.toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]) - normalized
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            val revision = (preferences[deleteRevisionKey] ?: 0L) + 1L
            preferences[deleteRevisionKey] = revision
            preferences[watchedDeleteVersionsKey] = (watchedVersions(preferences[watchedDeleteVersionsKey]) + normalized.associateWith { revision })
                .map { (key, value) -> gson.toJson(WatchedDeleteVersion(key, value)) }.toSet()
            preferences[watchedDeletesKey] = (parseWatchedDeletes(preferences[watchedDeletesKey]) + normalized)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun pendingWatchedUpserts(profileId: Int): Map<WatchedMutationKey, WatchedItem> {
        val preferences = store(profileId).data.first()
        return parseWatchedUpserts(preferences[watchedUpsertsKey])
    }

    suspend fun pendingWatchedDeletes(profileId: Int): Set<WatchedMutationKey> {
        return parseWatchedDeletes(store(profileId).data.first()[watchedDeletesKey])
    }

    suspend fun acknowledgeWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                val key = item.mutationKey()
                if (pending[key] == item) pending.remove(key)
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
        }
    }

    suspend fun watchedDeleteVersions(keys: Collection<WatchedMutationKey>, profileId: Int): Map<WatchedMutationKey, Long> {
        val versions = watchedVersions(store(profileId).data.first()[watchedDeleteVersionsKey])
        return keys.associateWith { versions[it] ?: 0L }
    }

    suspend fun acknowledgeWatchedDeletes(keys: Collection<WatchedMutationKey>, profileId: Int,
        expectedVersions: Map<WatchedMutationKey, Long>? = null) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            val versions = watchedVersions(preferences[watchedDeleteVersionsKey])
            val confirmed = keys.filter { expectedVersions == null || (versions[it] ?: 0L) == expectedVersions[it] }.toSet()
            preferences[watchedDeletesKey] = (parseWatchedDeletes(preferences[watchedDeletesKey]) - confirmed).map(gson::toJson).toSet()
            preferences[watchedDeleteVersionsKey] = (versions - confirmed)
                .map { (key, value) -> gson.toJson(WatchedDeleteVersion(key, value)) }.toSet()
        }
    }

    private fun progressVersions(raw: Set<String>?): Map<String, Long> = raw.orEmpty().mapNotNull {
        runCatching { gson.fromJson(it, ProgressDeleteVersion::class.java) }.getOrNull()
    }.associate { it.key to it.revision }

    private fun watchedVersions(raw: Set<String>?): Map<WatchedMutationKey, Long> = raw.orEmpty().mapNotNull {
        runCatching { gson.fromJson(it, WatchedDeleteVersion::class.java) }.getOrNull()
    }.associate { it.key to it.revision }

    suspend fun hasPendingProgressMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[progressUpsertsKey].isNullOrEmpty() ||
            !preferences[progressDeletesKey].isNullOrEmpty()
    }

    suspend fun hasPendingWatchedMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[watchedUpsertsKey].isNullOrEmpty() ||
            !preferences[watchedDeletesKey].isNullOrEmpty()
    }

    private fun parseProgressUpserts(raw: Set<String>?): Map<String, WatchProgress> {
        return raw.orEmpty().mapNotNull { json ->
            runCatching { gson.fromJson(json, PendingProgressUpsert::class.java) }.getOrNull()
        }.associate { it.key to it.progress }
    }

    private fun parseWatchedUpserts(raw: Set<String>?): Map<WatchedMutationKey, WatchedItem> {
        return raw.orEmpty().mapNotNull { json ->
            runCatching { gson.fromJson(json, PendingWatchedUpsert::class.java) }.getOrNull()
        }.associate { it.key to it.item }
    }

    private fun parseWatchedDeletes(raw: Set<String>?): Set<WatchedMutationKey> {
        return raw.orEmpty().mapNotNullTo(mutableSetOf()) { json ->
            runCatching { gson.fromJson(json, WatchedMutationKey::class.java) }.getOrNull()
        }
    }
}
