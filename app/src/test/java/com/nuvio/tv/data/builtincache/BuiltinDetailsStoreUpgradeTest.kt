package com.nuvio.tv.data.builtincache

import android.content.Context
import android.util.AtomicFile
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.squareup.moshi.Moshi
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class BuiltinDetailsStoreUpgradeTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    @Before fun setup() {
        context = mockk(relaxed = true)
        every { context.filesDir } returns temporary.newFolder("files")
        every { context.cacheDir } returns temporary.newFolder("cache")
        mockkConstructor(AtomicFile::class)
    }
    @After fun cleanup() { unmockkConstructor(AtomicFile::class) }
    private fun store() = BuiltinDetailsStore(context, Moshi.Builder().build(),
        Lazy { mockk<TmdbSettingsDataStore>(relaxed = true) }, mockk<SharedPlaybackIdentity>(relaxed = true))
    private val episodes = listOf(BuiltinDetailsStore.EpisodeKey("cinemana:1", 1, 1),
        BuiltinDetailsStore.EpisodeKey("cinemana:2", 1, 2))

    private fun oldCache(key: String, count: Int) {
        val directory = File(context.filesDir, "builtin-tmdb-bindings-v1").apply { mkdirs() }
        val target = File(directory, DiskStreamCache.digest(key) + ".json")
        target.writeText((1..count).joinToString(prefix = "[", postfix = "]") {
            """{"a":"cinemana:$it","b":1,"c":$it}"""
        })
        // Replace Android's atomic-file shell with real temporary-file IO, retaining
        // the production Gson/cache-validation and episode hashing code under test.
        every { constructedWith<AtomicFile>(EqMatcher(target)).readFully() } answers { target.readBytes() }
        every { constructedWith<AtomicFile>(EqMatcher(target)).startWrite() } answers { target.outputStream() }
        every { constructedWith<AtomicFile>(EqMatcher(target)).finishWrite(any()) } answers {
            firstArg<FileOutputStream>().close()
        }
    }

    @Test fun `old obfuscated episode cache is revalidated without escaping a null field hash failure`() = runBlocking {
        val store = store()
        for (count in 1..2) {
            val key = "upgrade-test-$count"
            oldCache("episodes|$key", count)
            var calls = 0
            assertEquals(true, store.verify(key, episodes) { unchecked, extending ->
                calls++; assertFalse(extending); assertEquals(episodes.toSet(), unchecked); true
            })
            assertEquals(true, store.verify(key, episodes) { _, _ ->
                fail("Fresh verified cache must be reused"); false
            })
            assertEquals(1, calls)
        }
    }

    @Test fun `old individual episode cache is rebuilt and the fresh proof remains reusable`() = runBlocking {
        val store = store()
        for (count in 1..2) {
            val key = "upgrade-individual-$count"
            oldCache("episode-runtimes|$key", count)
            var checked = 0
            repeat(2) {
                assertEquals(episodes.toSet(), store.verifyEpisodesIndividually(key, episodes.toSet()) { pending ->
                    checked += pending.size
                    pending.associateWith { true }
                })
            }
            assertEquals(2, checked)
        }
    }
}
