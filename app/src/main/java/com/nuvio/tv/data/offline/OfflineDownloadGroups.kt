package com.nuvio.tv.data.offline

sealed class OfflineDownloadEntry {
    data class Series(val key: String, val name: String, val episodes: List<OfflineItem>) : OfflineDownloadEntry()
    data class File(val item: OfflineItem) : OfflineDownloadEntry()
}

/** Group shows by catalog identity/aliases, never by their display names. Films stay at root. */
fun groupOfflineDownloads(items: List<OfflineItem>): List<OfflineDownloadEntry> {
    fun roots(item: OfflineItem): Set<String> = item.identity?.takeIf { it.type.lowercase() in setOf("series", "tv") }
        ?.keys()?.map { it.substringBeforeLast('|').substringBeforeLast('|') }?.toSet().orEmpty()
    val pending = items.filter { roots(it).isNotEmpty() }.toMutableList()
    val folders = mutableMapOf<String, OfflineDownloadEntry.Series>()
    while (pending.isNotEmpty()) {
        val members = mutableListOf(pending.removeAt(0))
        val aliases = roots(members.first()).toMutableSet()
        while (true) {
            val joined = pending.filter { roots(it).any(aliases::contains) }
            if (joined.isEmpty()) break
            joined.forEach { aliases.addAll(roots(it)) }
            members.addAll(joined)
            pending.removeAll(joined.toSet())
        }
        val title = members.firstNotNullOfOrNull { it.identity?.displayName?.takeIf(String::isNotBlank) } ?: members.first().title
        val folder = OfflineDownloadEntry.Series(aliases.sorted().first(), title,
            members.sortedWith(compareBy<OfflineItem> { it.identity?.season ?: Int.MAX_VALUE }
                .thenBy { it.identity?.episode ?: Int.MAX_VALUE }.thenBy { it.key }))
        members.forEach { folders[it.key] = folder }
    }
    val seen = mutableSetOf<String>()
    return items.asReversed().mapNotNull { item ->
        val folder = folders[item.key]
        if (folder == null) OfflineDownloadEntry.File(item) else folder.takeIf { seen.add(it.key) }
    }
}
