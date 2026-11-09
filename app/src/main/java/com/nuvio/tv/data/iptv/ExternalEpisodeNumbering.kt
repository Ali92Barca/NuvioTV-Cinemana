package com.nuvio.tv.data.iptv

/** Recognize a complete cumulative season without guessing from a single episode. */
internal object ExternalEpisodeNumbering {
    /** Same-work translated editions may have different release availability. Never invent
     * a coordinate for an absent episode or close a numbering gap by shifting later episodes. */
    fun translatedMap(canonical: Map<Int, Set<Int>>, native: Map<Int, List<Int>>,
        season: Int, nativeSeason: Int): Map<Int, Int>? {
        if (season != nativeSeason || season <= 0) return null
        val expected = canonical[season].orEmpty()
        val actual = native[nativeSeason].orEmpty()
        if (expected.isEmpty() || expected != (1..expected.size).toSet() || actual.isEmpty() ||
            actual.distinct().size != actual.size) return null
        val sorted = actual.sorted()
        val offset = if (sorted.first() == 1) 0 else {
            var preceding = 0
            for (previous in 1 until season) {
                val source = native[previous].orEmpty()
                val local = (1..source.size).toSet()
                if (source.isEmpty() || source.distinct().size != source.size ||
                    (source.toSet() != local && source.toSet() != local.map { it + preceding }.toSet())) return null
                preceding += source.size
            }
            preceding.takeIf { it > 0 && sorted.first() == it + 1 } ?: return null
        }
        if (sorted != (offset + 1..offset + sorted.size).toList()) return null
        return expected.filter { it + offset in actual }.associateWith { it + offset }.takeIf { it.isNotEmpty() }
    }
    /** Turkish editions with equal season counts use sorted positions, independent of labels/dates. */
    fun episodeMap(canonical: Map<Int, Set<Int>>, native: Map<Int, List<Int>>, season: Int,
        nativeSeason: Int, aired: Set<Int>, arabic: Boolean, turkish: Boolean = false): Map<Int, Int>? {
        val complete = canonical[season].orEmpty()
        val actual = native[nativeSeason].orEmpty()
        if (turkish) {
            if (complete.isEmpty() || actual.isEmpty() || actual.any { it <= 0 } ||
                actual.distinct().size != actual.size) return null
            // A recut edition cannot become a match because a later planned season happens
            // to have the same count. Missing earlier seasons do not block a partial catalog.
            if (season == nativeSeason && (1 until season).any { previous ->
                val expected = canonical[previous]
                val source = native[previous]
                expected != null && source != null && expected.size != source.size
            }) return null
            val expected = when {
                actual.size == complete.size -> complete
                aired.isNotEmpty() && complete.containsAll(aired) && actual.size == aired.size -> aired
                else -> return null
            }
            return expected.sorted().zip(actual.sorted()).toMap()
        }
        val offset = offset(canonical, native, season, nativeSeason, aired, arabic) ?: return null
        return complete.filter { it + offset in actual }.associateWith { it + offset }
    }
    fun acceptsLayout(arabic: Boolean, expected: Set<Int>, actual: Set<Int>, aired: Set<Int>) =
        arabic || expected == actual ||
            (aired.isNotEmpty() && aired == actual && expected.containsAll(aired))

    /** Add this offset to a canonical episode number to obtain its native coordinate. */
    fun offset(
        canonical: Map<Int, Set<Int>>,
        native: Map<Int, List<Int>>,
        season: Int,
        nativeSeason: Int,
        aired: Set<Int>,
        arabic: Boolean
    ): Int? {
        val expected = canonical[season].orEmpty()
        val actual = native[nativeSeason].orEmpty()
        if (acceptsLayout(arabic, expected, actual.toSet(), aired)) return 0
        // Numbered-part aliases are a separate proof, never an inferred season offset.
        if (season != nativeSeason || season <= 1 || expected.isEmpty() ||
            expected != (1..expected.size).toSet() || actual.size != actual.toSet().size) return null
        var preceding = 0
        for (previous in 1 until season) {
            val expectedPrevious = canonical[previous].orEmpty()
            val sourcePrevious = native[previous].orEmpty()
            if (expectedPrevious.isEmpty() || expectedPrevious != (1..expectedPrevious.size).toSet() ||
                sourcePrevious.size != expectedPrevious.size || sourcePrevious.size != sourcePrevious.toSet().size) return null
            val cumulativePrevious = expectedPrevious.map { it + preceding }.toSet()
            if (sourcePrevious.toSet() != expectedPrevious && sourcePrevious.toSet() != cumulativePrevious) return null
            preceding += expectedPrevious.size
        }
        val shifted = actual.map { it - preceding }.toSet()
        return preceding.takeIf { acceptsLayout(false, expected, shifted, aired) }
    }
}
