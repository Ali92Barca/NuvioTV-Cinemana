package com.nuvio.tv.data.repository

/** A currently airing series may expose only a subset of official episodes. */
internal fun verifiedEpisodeCoordinates(native: List<Pair<Int, Int>>, official: Set<Pair<Int, Int>>): Boolean =
    native.isNotEmpty() && official.isNotEmpty() && native.distinct().size == native.size && native.all { it in official }
