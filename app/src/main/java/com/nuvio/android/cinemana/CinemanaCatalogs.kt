package com.nuvio.android.cinemana

/**
 * Catalog IDs and category mappings ported from Ali92Barca/cinemana-addon/server.js.
 * These are deliberately independent of the Nuvio UI integration.
 */
object CinemanaCatalogs {
    data class Category(val id: String, val title: String, val remoteIds: String)

    val categories = listOf(
        Category("cinemana_action", "أكشن", "97,84"),
        Category("cinemana_thriller", "إثارة", "80"),
        Category("cinemana_crime", "جريمة", "60"),
        Category("cinemana_western", "حياة الغرب", "89"),
        Category("cinemana_scifi", "خيال علمي", "78"),
        Category("cinemana_fantasy", "خيالي", "67"),
        Category("cinemana_drama", "دراما", "62"),
        Category("cinemana_animation", "رسوم متحركة", "57"),
        Category("cinemana_horror", "رعب", "70"),
        Category("cinemana_romance", "رومانسي", "77"),
        Category("cinemana_sport", "رياضي", "79"),
        Category("cinemana_mystery", "غموض", "76"),
        Category("cinemana_comedy", "كوميدي", "59"),
        Category("cinemana_adventure", "مغامرة", "56"),
        Category("cinemana_documentary", "وثائقي", "61")
    )

    fun category(id: String): Category? =
        categories.firstOrNull { it.id == id.removeSuffix("_series") }

    fun isSeriesCatalog(id: String): Boolean = id.endsWith("_series")
}
