package com.nuvio.android.cinemana

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CinemanaBilingualIdentityTest {
    @Test fun arabicNativeSearchKeepsOriginalSpellingInsteadOfSendingComparisonKey() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val matcher = CinemanaTmdbMatcher("fixture") { url ->
            val query = java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8")
            queries += query
            if (query == "مناعة") """{"results":[{"id":302770,"name":"مناعة","original_name":"مناعة"}]}"""
            else """{"results":[]}"""
        }
        val names = com.nuvio.tv.data.iptv.ExternalAddonTitleMatcher.searchTitles("مناعة")
        assertEquals(302770, matcher.match(names, null, true)?.tmdbId)
        assertTrue(queries.all { it == "مناعة" })
        assertNull(matcher.match(com.nuvio.tv.data.iptv.ExternalAddonTitleMatcher.titleVariants("مناعة").toList(), null, true))
    }
    private val arabic = "علي كارا"
    @Test fun sharedOriginalNameIsDisambiguatedByAnIndependentExactArabicName() = runBlocking {
        val matcher = CinemanaTmdbMatcher("fixture") { url ->
            if ("language=ar-SA" in url) """{"results":[{"id":256440,"name":"$arabic","original_name":"Alıkara"},{"id":333071,"name":"Alıkara","original_name":"Alıkara"}]}"""
            else """{"results":[{"id":256440,"name":"Alıkara","original_name":"Alıkara"},{"id":333071,"name":"Alıkara","original_name":"Alıkara"}]}"""
        }
        assertEquals(256440,matcher.match(listOf("Alıkara",arabic),null,true)?.tmdbId)
        assertNull(matcher.match(listOf("Alıkara"),null,true))
    }
    @Test fun repeatedLocalesNeverBreakATieBetweenTwoWorksWithTheSameAliases() = runBlocking {
        val matcher = CinemanaTmdbMatcher("fixture") {
            """{"results":[{"id":1,"name":"$arabic","original_name":"Alıkara"},{"id":2,"name":"$arabic","original_name":"Alıkara"}]}"""
        }
        assertNull(matcher.match(listOf("Alıkara",arabic),null,true))
    }
}
