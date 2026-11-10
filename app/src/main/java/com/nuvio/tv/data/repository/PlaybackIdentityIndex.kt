package com.nuvio.tv.data.repository

/** Derived lookup only. Original row order is significant when aliases overlap. */
internal class PlaybackIdentityIndex(val rows: List<PlaybackIdentity>) {
    private val native = rows.groupBy { it.native }
    private val aliases = buildMap<String, MutableList<Int>> {
        rows.forEachIndexed { index, row ->
            row.aliases.forEach { alias -> getOrPut(alias) { mutableListOf() }.add(index) }
        }
    }
    fun native(id: String?, seriesHint: Boolean? = null): PlaybackIdentity? =
        native[id]?.firstOrNull { seriesHint == null || it.series == seriesHint }
    fun alias(id: String): List<PlaybackIdentity> = aliases[id].orEmpty().map(rows::get)
    fun matching(ids: Set<String>): List<PlaybackIdentity> = ids.flatMap { aliases[it].orEmpty() }
        .distinct().sorted().map(rows::get)
}
