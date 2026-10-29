package com.nuvio.android.cinemana

/** A season may carry a later year than the show's first broadcast. Only
 * accept that difference when TMDB's verified air-date interval explains it. */
object CinemanaIdentity {
    fun yearMatches(candidate: Int?, first: Int?, last: Int?, series: Boolean): Boolean {
        if (candidate == null || first == null) return true
        if (!series) return candidate == first
        if (candidate == first) return true
        return last != null && last >= first && candidate in first..last
    }
}
