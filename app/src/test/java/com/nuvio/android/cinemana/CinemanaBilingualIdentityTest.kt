package com.nuvio.android.cinemana

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CinemanaBilingualIdentityTest {
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
