package com.nuvio.tv.data.offline

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReadinessMonitorTest {
    @Test fun stableDownloadsWaitButCompletionEventsRefreshImmediately() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var calls = 0
        var snapshot = OfflineDownloads.Readiness(setOf("old"), false)
        val values = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeOfflineReadyKeys(events) { calls++; snapshot }.collect { values += it }
        }
        runCurrent()
        val initial = calls
        advanceTimeBy(29_999); runCurrent()
        assertEquals(initial, calls)
        snapshot = OfflineDownloads.Readiness(setOf("new"), false)
        events.emit(Unit); runCurrent()
        assertEquals(setOf("new"), values.last())
        assertEquals(initial + 1, calls)
        job.cancelAndJoin()
        val stopped = calls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(stopped, calls)
    }
    @Test fun activeTransfersPollQuicklyAndReadErrorsDoNotEraseKnownBadges() = runTest {
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var calls = 0
        var failed = false
        val values = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeOfflineReadyKeys(events) {
                calls++
                if (failed) error("USB temporarily unavailable")
                OfflineDownloads.Readiness(setOf("ready"), true)
            }.collect { values += it }
        }
        runCurrent(); val initial = calls
        advanceTimeBy(2_000); runCurrent()
        assertEquals(initial + 1, calls)
        failed = true; events.emit(Unit); runCurrent()
        assertEquals(listOf(setOf("ready")), values)
        job.cancelAndJoin()
    }
}
