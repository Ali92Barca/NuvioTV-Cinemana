package com.nuvio.tv.data.cee

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CeeResolverTest {
    private val query = CeeResolver.Query("tt123", null, "Example", 2020, "movie")
    private fun item(id: Int, imdb: String, year: Int = 2020) = JSONObject()
        .put("nb", "$id").put("kind", "1").put("en_title", "Example")
        .put("year", "$year").put("imdbUrlRef", imdb)
    @Test fun rejectsConflictingIdentityAndRemakes() {
        assertNull(CeeResolver.match(query, listOf(item(1, "tt999"))))
        assertNull(CeeResolver.match(query, listOf(item(1, "", 2021))))
        assertEquals("1", CeeResolver.match(query, listOf(item(1, "tt123"))))
    }
    @Test fun ambiguousTitleDoesNotPickArbitraryVideo() {
        assertNull(CeeResolver.match(query, listOf(item(1, ""), item(2, ""))))
    }
}
