package com.nuvio.android.cinemana
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CinemanaSearchConcurrencyTest {
    @Test fun startsPagesTogetherAndRetainsLaterEdition() = runBlocking {
        val started = mutableSetOf<String>()
        val allStarted = CompletableDeferred<Unit>()
        val client = CinemanaClient(transport = { url ->
            started.add(url.substringAfter("&page=").substringBefore('&'))
            if (started.size == 5) allStarted.complete(Unit)
            allStarted.await()
            when {
                "page=0&" in url -> """[{"nb":"1","en_title":"Example","year":"2020"}]"""
                "page=1&" in url -> """[{"nb":"2","en_title":"Example Dubbed","year":"2020"}]"""
                else -> "[]"
            }
        })
        val entries = withTimeout(2000) { client.search("Example", false) }
        assertEquals(listOf("1", "2"), entries.map { it.id })
        assertEquals(5, started.size)
    }
}
