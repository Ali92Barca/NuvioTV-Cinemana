package com.nuvio.tv.data.offline

import android.app.DownloadManager
import org.junit.Assert.*
import org.junit.Test

class OfflineReadinessTest {
    private val item = OfflineItem("film", "Film", 1L, listOf(2L to "ar"),
        identity = OfflineMediaIdentity("movie", "tt123", null, null, null))
    private val complete = mapOf(1L to DownloadManager.STATUS_SUCCESSFUL, 2L to DownloadManager.STATUS_SUCCESSFUL)
    @Test fun requiresAllFilesAndSuccessfulStatusesOnAvailableStorage() {
        assertEquals(item.identity!!.keys(), readyOfflineKeys(listOf(item), complete, { true }, { false }))
        assertTrue(readyOfflineKeys(listOf(item), complete - 2L, { true }, { false }).isEmpty())
        assertTrue(readyOfflineKeys(listOf(item), complete, { it != 1L }, { false }).isEmpty())
        assertTrue(readyOfflineKeys(listOf(item), complete, { it != 2L }, { false }).isEmpty())
        assertTrue(readyOfflineKeys(listOf(item), complete, { true }, { true }).isEmpty())
        assertTrue(readyOfflineKeys(listOf(item), complete + (1L to DownloadManager.STATUS_FAILED), { true }, { false }).isEmpty())
    }
    @Test fun pausedTransfersUseSlowPollingAndRunningTransfersUseFastPolling() {
        assertFalse(hasActiveOfflineTransfers(listOf(DownloadManager.STATUS_PAUSED)))
        assertFalse(hasActiveOfflineTransfers(complete.values))
        assertTrue(hasActiveOfflineTransfers(listOf(DownloadManager.STATUS_PENDING)))
        assertTrue(hasActiveOfflineTransfers(listOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)))
    }
}
