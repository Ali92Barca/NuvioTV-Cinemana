package com.nuvio.tv.data.repository

/** One bounded snapshot, shared by readers without replaying another profile's
 * state or starting another background subscription for every detail screen. */
internal class HistoryProjectionCache<T, R> {
    private data class Snapshot<T, R>(val profile: Int, val layout: Any, val revision: Any, val input: List<T>, val result: R)
    private var cached: Snapshot<T, R>? = null

    @Synchronized
    fun get(profile: Int, layout: Any, revision: Any, input: List<T>, project: () -> R): R {
        cached?.let {
            if (it.profile == profile && it.layout == layout && it.revision == revision &&
                (it.input === input || it.input == input)) return it.result
        }
        return project().also { cached = Snapshot(profile, layout, revision, input, it) }
    }
}
