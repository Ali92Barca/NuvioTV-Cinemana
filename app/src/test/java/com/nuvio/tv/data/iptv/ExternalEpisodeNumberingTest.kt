package com.nuvio.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test

class ExternalEpisodeNumberingTest {
    @Test fun translatedSharedEpisodesSurviveDifferentAvailabilityAndFutureGrowth() {
        val source = mapOf(1 to (1..10).toList())
        val first = ExternalEpisodeNumbering.translatedMap(mapOf(1 to (1..8).toSet()), source, 1, 1)!!
        assertEquals((1..8).associateWith { it }, first)
        assertFalse(first.containsKey(9))
        val later = ExternalEpisodeNumbering.translatedMap(mapOf(1 to (1..9).toSet()), source, 1, 1)!!
        assertEquals(9, later[9])
        assertFalse(later.containsKey(10))
        assertEquals((1..8).associateWith { it }, ExternalEpisodeNumbering.translatedMap(
            mapOf(1 to (1..10).toSet()), mapOf(1 to (1..8).toList()), 1, 1))
    }
    @Test fun abiSecondSeasonMapsNineteenToOneWithoutChangingNativeCoordinates() {
        val layout = mapOf(1 to (1..18).toSet(), 2 to (1..5).toSet())
        val source = mapOf(1 to (1..18).toList(), 2 to (19..23).toList())
        assertEquals((1..5).associateWith { it + 18 }, ExternalEpisodeNumbering.episodeMap(
            layout, source, 2, 2, (1..5).toSet(), false, true))
        assertEquals((1..5).associateWith { it + 18 }, ExternalEpisodeNumbering.translatedMap(layout, source, 2, 2))
        assertEquals((1..4).associateWith { it + 18 }, ExternalEpisodeNumbering.translatedMap(
            layout + (2 to (1..4).toSet()), source, 2, 2))
    }
    @Test fun translatedPartialMappingRejectsGapsDuplicatesUnknownOffsetsAndSeparateParts() {
        val layout = mapOf(1 to (1..8).toSet())
        listOf(listOf(1,2,4,5), listOf(1,2,2,3), (2..10).toList()).forEach { source ->
            assertNull(ExternalEpisodeNumbering.translatedMap(layout, mapOf(1 to source), 1, 1))
        }
        assertNull(ExternalEpisodeNumbering.translatedMap(layout, mapOf(2 to (1..10).toList()), 1, 2))
        assertNull(ExternalEpisodeNumbering.translatedMap(mapOf(2 to (1..5).toSet()), mapOf(2 to (19..23).toList()), 2, 2))
    }
    private val canonical = mapOf(1 to (1..28).toSet(), 2 to (1..35).toSet(), 3 to (1..35).toSet())
    private val native = mapOf(1 to (1..28).toList(), 2 to (29..63).toList(), 3 to (64..71).toList())
    private fun offset(season: Int = 2, source: Map<Int, List<Int>> = native,
        expected: Map<Int, Set<Int>> = canonical, aired: Set<Int> = expected[season].orEmpty()) =
        ExternalEpisodeNumbering.offset(expected, source, season, season, aired, false)

    @Test fun turkishEqualCountsUseOrderEvenWhenEarlierSeasonsAreNotInTheCatalog() {
        val map = ExternalEpisodeNumbering.episodeMap(canonical,
            mapOf(2 to (29..63).reversed().toList()), 2, 2, (1..35).toSet(), false, true)!!
        assertEquals(36, map[8])
        assertEquals(63, map[35])
        assertEquals((1..35).toSet(), map.keys)
    }
    @Test fun turkishCurrentSeasonUsesAiredCountAndRejectsRecutEditions() {
        assertEquals(71, ExternalEpisodeNumbering.episodeMap(canonical, native, 3, 3,
            (1..8).toSet(), false, true)!![8])
        assertNull(ExternalEpisodeNumbering.episodeMap(canonical,
            mapOf(1 to (1..140).toList(), 2 to (1..175).toList(), 3 to (1..35).toList()),
            3, 3, (1..8).toSet(), false, true))
        assertNull(ExternalEpisodeNumbering.episodeMap(canonical, native + (2 to (29..62).toList()),
            2, 2, (1..35).toSet(), false, true))
    }
    @Test fun turkishDuplicateCoordinatesCannotBeMappedByOrder() {
        assertNull(ExternalEpisodeNumbering.episodeMap(canonical,
            mapOf(2 to ((29..62).toList() + 62)), 2, 2, (1..35).toSet(), false, true))
    }

    @Test fun cumulativeSecondSeasonKeepsAllThirtyFiveEpisodes() {
        assertEquals(28, offset())
        assertEquals(36, 8 + offset()!!)
        assertEquals(63, 35 + offset()!!)
    }
    @Test fun ongoingThirdSeasonUsesVerifiedAiredPrefixAndAllPreviousSeasons() {
        assertEquals(63, offset(3, aired = (1..8).toSet()))
        assertNull(offset(3))
    }
    @Test fun recutPreviousSeasonCannotSupplyAnOffset() {
        assertNull(offset(source = native + (1 to (1..140).toList())))
    }
    @Test fun missingPreviousSeasonEvidenceCannotSupplyAnOffset() {
        assertNull(offset(expected = canonical - 1))
        assertNull(offset(source = native - 1))
    }
    @Test fun gapsDuplicatesAndShiftedStartsAreRejected() {
        assertNull(offset(source = native + (2 to ((29..63).toList() - 36))))
        assertNull(offset(source = native + (2 to ((29..63).toList() + 36))))
        assertNull(offset(source = native + (2 to (30..64).toList())))
    }
    @Test fun localNumberingStillWorksIndependently() {
        assertEquals(0, offset(source = mapOf(2 to (1..35).toList())))
    }
    @Test fun arabicExceptionAndExplicitPartMappingKeepTheirExistingCoordinates() {
        assertEquals(0, ExternalEpisodeNumbering.offset(canonical, native, 2, 2, emptySet(), true))
        assertEquals(0, ExternalEpisodeNumbering.offset(mapOf(1 to setOf(1, 2)),
            mapOf(2 to listOf(1, 2)), 1, 2, setOf(1, 2), false))
        assertNull(ExternalEpisodeNumbering.offset(canonical, native, 1, 2, (1..28).toSet(), false))
    }
}
