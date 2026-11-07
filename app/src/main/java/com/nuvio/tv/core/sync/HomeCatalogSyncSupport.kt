package com.nuvio.tv.core.sync

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.catalogTypeKey
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons

internal data class LocalHomeCatalogSettingsState(
    val orderKeys: List<String> = emptyList(),
    val disabledKeys: Set<String> = emptySet(),
    val customTitles: Map<String, String> = emptyMap(),
    val hideUnreleasedContent: Boolean = false
)

private data class HomeCatalogSyncEntry(
    val key: String,
    val addonId: String = "",
    val addonBaseUrl: String = "",
    val type: String = "",
    val catalogId: String = "",
    val catalogName: String = "",
    val isCollection: Boolean = false,
    val collectionId: String = ""
)

internal fun homeCatalogKey(addonId: String, type: String, catalogId: String): String {
    return catalogRowLegacyKey(addonId, type, catalogId)
}

internal fun homeCollectionKey(collectionId: String): String {
    return "collection_${collectionId}"
}

internal fun List<String>.withLocalServerKeys(local: List<String>): List<String> =
    local.withIndex()
        .filter { ServerCatalog.isServerKey(it.value) }
        .fold(filterNot(ServerCatalog::isServerKey).toMutableList()) { keys, (index, key) ->
            keys.apply { add(index.coerceAtMost(size), key) }
        }

internal fun homeLegacyDisabledCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${catalogTypeKey(type)}_${catalogId}_${catalogName}"
}

internal fun hasLegacyHomeCatalogDisabledKeyFormat(key: String): Boolean {
    return key.contains("://")
}

internal fun isOptInHomeCatalogAddon(addonId: String): Boolean =
    addonId == "org.nuvio.cinemana.builtin" || addonId == "org.nuvio.cee"

internal fun CatalogDescriptor.isSelectableHomeCatalog(addonId: String): Boolean =
    !isSearchOnlyCatalog() && !(addonId == "org.nuvio.cee" && id in ceeSearchCatalogIds)

private val ceeSearchCatalogIds = setOf(
    com.nuvio.tv.data.cee.CeeResourceAdapter.SEARCH_MOVIES_CATALOG,
    com.nuvio.tv.data.cee.CeeResourceAdapter.SEARCH_SERIES_CATALOG
)

private const val HOME_CATALOG_ENABLED_PREFIX = "home_catalog_enabled:"

internal fun homeCatalogEnabledKey(key: String): String = HOME_CATALOG_ENABLED_PREFIX + key

internal fun isHomeCatalogEnabledKey(key: String): Boolean = key.startsWith(HOME_CATALOG_ENABLED_PREFIX)

internal fun isHomeCatalogDisabled(
    disabledKeys: Set<String>,
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    val key = homeCatalogKey(addonId, type, catalogId)
    if (isOptInHomeCatalogAddon(addonId)) return homeCatalogEnabledKey(key) !in disabledKeys
    return key in disabledKeys ||
        homeLegacyDisabledCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledKeys
}

internal fun buildHomeCatalogSyncPayload(
    addons: List<Addon>,
    collections: List<Collection>,
    localState: LocalHomeCatalogSettingsState
): SyncHomeCatalogPayload {
    val catalogEntries = buildCatalogEntries(addons)
    val collectionEntries = collections.map { collection ->
        HomeCatalogSyncEntry(
            key = homeCollectionKey(collection.id),
            isCollection = true,
            collectionId = collection.id
        )
    }
    val entryByKey = (catalogEntries + collectionEntries).associateBy { it.key }
    val catalogKeys = catalogEntries.map { it.key }
    val collectionKeys = collectionEntries.map { it.key }

    val savedValid = localState.orderKeys
        .asSequence()
        .filter { it in entryByKey }
        .distinct()
        .toList()
    val savedSet = savedValid.toSet()
    val mergedOrder = savedValid +
        catalogKeys.filterNot { it in savedSet } +
        collectionKeys.filterNot { it in savedSet }

    val items = mergedOrder.mapIndexedNotNull { index, key ->
        val entry = entryByKey[key] ?: return@mapIndexedNotNull null
        if (entry.isCollection) {
            SyncCatalogItem(
                addonId = "",
                type = "",
                catalogId = "",
                enabled = key !in localState.disabledKeys,
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = true,
                collectionId = entry.collectionId
            )
        } else {
            SyncCatalogItem(
                addonId = entry.addonId,
                type = entry.type,
                catalogId = entry.catalogId,
                enabled = !isHomeCatalogDisabled(
                    disabledKeys = localState.disabledKeys,
                    addonId = entry.addonId,
                    addonBaseUrl = entry.addonBaseUrl,
                    type = entry.type,
                    catalogId = entry.catalogId,
                    catalogName = entry.catalogName
                ),
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = false,
                collectionId = ""
            )
        }
    }

    return SyncHomeCatalogPayload(
        hideUnreleasedContent = localState.hideUnreleasedContent,
        items = items
    )
}

private fun buildCatalogEntries(addons: List<Addon>): List<HomeCatalogSyncEntry> {
    val entries = mutableListOf<HomeCatalogSyncEntry>()
    val seenKeys = mutableSetOf<String>()

    addons.enabledAddons().forEach { addon ->
        addon.catalogs
            .filter { it.isSelectableHomeCatalog(addon.id) && (isOptInHomeCatalogAddon(addon.id) || it.shouldShowOnHomeForSync()) }
            .forEach { catalog ->
                val key = homeCatalogKey(addon.id, catalog.apiType, catalog.id)
                if (seenKeys.add(key)) {
                    entries.add(
                        HomeCatalogSyncEntry(
                            key = key,
                            addonId = addon.id,
                            addonBaseUrl = addon.baseUrl,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name
                        )
                    )
                }
            }
    }

    return entries
}

private fun CatalogDescriptor.shouldShowOnHomeForSync(): Boolean {
    if (isSearchOnlyCatalog()) {
        return false
    }
    return !hasExplicitShowInHome || showInHome
}
