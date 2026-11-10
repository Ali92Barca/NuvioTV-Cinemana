package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class OfflineIndexCacheTest {
    @Test fun unchangedLargePayloadReusesParsedIndexAndExternalPreferenceChangesInvalidateIt() {
        val prefs = mockk<SharedPreferences>()
        val manager = mockk<DownloadManager>()
        val context = mockk<Context> {
            every { getSharedPreferences("cinemana_offline_v1", any()) } returns prefs
            every { getSystemService(Context.DOWNLOAD_SERVICE) } returns manager
        }
        val payload = "x".repeat(256 * 1024)
        var raw = """[{"key":"one","title":"Film","video":1,"subtitles":[],"sceneSkipPayload":"$payload"}]"""
        every { prefs.getString("items", "[]") } answers { raw }
        val downloads = OfflineDownloads(context)
        val first = downloads.list()
        assertEquals(payload, first.single().sceneSkipPayload)
        assertSame(first, downloads.list())
        raw = "[]"
        assertTrue(downloads.list().isEmpty())
        assertTrue(downloads.readiness().keys.isEmpty())
        verify(exactly = 0) { manager.query(any()) }
    }
}
