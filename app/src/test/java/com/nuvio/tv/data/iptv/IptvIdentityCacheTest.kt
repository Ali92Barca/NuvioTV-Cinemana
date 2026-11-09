package com.nuvio.tv.data.iptv

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class IptvIdentityCacheTest {
    private val files = mutableMapOf<String, MutableMap<String, Any?>>()
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } answers {
            val values = files.getOrPut(firstArg()) { mutableMapOf() }
            val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
                every { putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); this@mockk }
                every { putStringSet(any(), any()) } answers { values[firstArg()] = secondArg<Set<String>>().toSet(); this@mockk }
                every { putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); this@mockk }
                every { clear() } answers { values.clear(); this@mockk }
                every { commit() } returns true
            }
            mockk<SharedPreferences> {
                every { getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
                every { getStringSet(any(), any()) } answers { @Suppress("UNCHECKED_CAST") ((values[firstArg()] as? Set<String>) ?: secondArg<Set<String>>()).toMutableSet() }
                every { getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
                every { edit() } returns editor
            }
        }
    }
    @Test fun translatedCatalogIsLanguageIndependentAndRequiresTheWord() {
        listOf("مسلسلات أجنبية مترجمة", "تركي مترجم", "هندي مترجمة", "مترجم").forEach { assertTrue(IptvIdentityCache.translatedCatalog(it)) }
        listOf("تركي مدبلج", "مسلسلات أجنبية", "غيرمترجم").forEach { assertFalse(IptvIdentityCache.translatedCatalog(it)) }
    }
    @Test fun survivesRecreationIsolatesSubscriptionsAndDeletesOnlyRemovedAddon() {
        val a = "https://example.test/private-secret/manifest.json"
        val b = "https://example.test/other-secret/manifest.json"
        val cache = IptvIdentityCache(context)
        cache.rememberCatalog(a, "أجنبي مترجم", listOf("xt_s_src_1"))
        cache.rememberWork(a, "xt_s_src_1", "42", "tt12345", true)
        cache.rememberWork(b, "xt_s_src_1", "99", null, false)
        cache.saveEpisodes(cache.scope(a), "[]")
        val recreated = IptvIdentityCache(context)
        assertEquals("42", recreated.work(a, "xt_s_src_1")!!.get("tmdb").asString)
        assertTrue(recreated.translated(a, "xt_s_src_1"))
        assertFalse(recreated.translated(b, "xt_s_src_1"))
        recreated.remove(a)
        assertNull(recreated.work(a, "xt_s_src_1"))
        assertFalse(recreated.translated(a, "xt_s_src_1"))
        assertEquals("99", recreated.work(b, "xt_s_src_1")!!.get("tmdb").asString)
        assertTrue(recreated.episodeFiles().isEmpty())
        assertFalse(files.toString().contains("private-secret"))
        recreated.rememberWork(a, "xt_s_src_1", "43", null, true)
        assertEquals("43", recreated.work(a, "xt_s_src_1")!!.get("tmdb").asString)
    }
    @Test fun storedWorkCannotBeSilentlyRematched() {
        val cache = IptvIdentityCache(context)
        cache.rememberWork("a", "xt_s_src_1", "42", null, true)
        cache.rememberWork("a", "xt_s_src_1", "99", null, true)
        assertEquals("42", cache.work("a", "xt_s_src_1")!!.get("tmdb").asString)
    }
}
