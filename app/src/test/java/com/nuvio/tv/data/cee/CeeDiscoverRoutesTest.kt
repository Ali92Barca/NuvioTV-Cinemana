package com.nuvio.tv.data.cee

import com.nuvio.android.cinemana.CinemanaClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CeeDiscoverRoutesTest {
    @Test fun ceeRoutesUseTheSamePaginationAndFiltersAsTizen() = runBlocking {
        val paths = mutableListOf<String>()
        val client = CeeClient(OkHttpClient()) { path ->
            paths += path
            "{\"videos\":[{\"nb\":123}]}"
        }
        assertEquals(1, client.discover(series = false, page = 1, sort = "asc").length())
        assertEquals("video/V/2/itemsPerPage/30/level/0/videoKind/1/sortParam/asc/pageNumber/1", paths.last())
        val category = CeeDiscoverFilters.categoryNames.first { CeeDiscoverFilters.languages(it).any { lang -> lang.series > 0 } }
        val id = CeeDiscoverFilters.categoryId(category)
        val language = CeeDiscoverFilters.languages(category).first { it.series > 0 }
        client.discover(series = true, page = 2, category = category, sort = "title_asc", language = language.id)
        assertEquals("videosByCategoryAndLanguage?category_id=$id&language_id=${language.id}&videoKind=2&orderby=title_asc&level=0&offset=60", paths.last())
        client.discover(series = true, page = 2, category = category, sort = "desc")
        assertEquals("videosByCategory?categoryID=$id&orderby=desc&videoKind=2&offset=60&level=0", paths.last())
        val years = CeeClient(OkHttpClient()) { path -> paths += path; "[]" }
        years.discover(series = false, startYear = 2020, endYear = 2020, category = "Action")
        assertTrue(paths.last().contains("year=2020%2C2020&type=movies&category=Action"))
        assertFalse(paths.last().contains("orderby"))
        assertEquals(30, CeeDiscoverFilters.pageSize(false, category, "desc"))
        assertEquals(12, CeeDiscoverFilters.pageSize(true, category, null))
    }

    @Test fun cinemanaRoutesGlobalSortAndCategoryYearSeparately() = runBlocking {
        val paths = mutableListOf<String>()
        val client = CinemanaClient("https://example.invalid") { url -> paths += url; "[]" }
        client.allCatalogPage(series = true, page = 1, sort = "views_desc")
        assertTrue(paths.last().endsWith("/video/V/2/itemsPerPage/30/level/0/videoKind/2/sortParam/views_desc/pageNumber/1"))
        client.allCatalogPage(false, 2, "2020", "84")
        assertTrue(paths.last().endsWith("/AdvancedSearch?level=0&category_id=84&type=movies&page=2&year=2020%2C2020"))
    }
}
