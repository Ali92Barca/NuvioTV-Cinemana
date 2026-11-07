package com.nuvio.android.cinemana

import org.junit.Assert.assertEquals
import org.junit.Test

class CinemanaEpisodeMappingRegressionTest {
    private val counts = mapOf(1 to 28, 2 to 35, 3 to 20)

    @Test
    fun absolute70EpisodesSplitIntoTwoSourceSeasonsMapToThreeTmdbSeasons() {
        val source = (1..70).map { absolute ->
            CinemanaEpisodeMapping.EpisodeRef(
                videoId = (100000 + absolute).toString(),
                season = if (absolute <= 35) 1 else 2,
                episode = absolute,
                title = "Episode $absolute"
            )
        }

        val mapped = CinemanaEpisodeMapping.normalizeContinuous(source, counts)

        assertEquals(70, mapped.size)
        assertEquals(28, mapped.count { it.season == 1 })
        assertEquals(35, mapped.count { it.season == 2 })
        assertEquals(7, mapped.count { it.season == 3 })

        assertEquals(1 to 28, mapped[27].let { it.season to it.episode })
        assertEquals(2 to 1, mapped[28].let { it.season to it.episode })
        assertEquals(2 to 35, mapped[62].let { it.season to it.episode })
        assertEquals(3 to 1, mapped[63].let { it.season to it.episode })
        assertEquals(3 to 7, mapped[69].let { it.season to it.episode })

        // Playback must continue to reference the exact Cinemana video.
        assertEquals(source.map { it.videoId }, mapped.map { it.videoId })
    }

    @Test
    fun alreadyNumberedSeasonsWithResettingEpisodeNumbersRemainUnchanged() {
        val source = (1..28).map {
            CinemanaEpisodeMapping.EpisodeRef(
                (200000 + it).toString(), 1, it, "Episode $it"
            )
        } + (1..35).map {
            CinemanaEpisodeMapping.EpisodeRef(
                (300000 + it).toString(), 2, it, "Episode $it"
            )
        }

        assertEquals(
            source,
            CinemanaEpisodeMapping.normalizeContinuous(source, counts)
        )
    }


    @Test
    fun shortCinemanaEditionMustNotMatchLongOfficialEpisode() {
        assertEquals(
            false,
            CinemanaEpisodeMapping.durationCompatible(40L * 60, 120)
        )
    }

    @Test
    fun matchingEpisodeRuntimeIsAllowed() {
        assertEquals(
            true,
            CinemanaEpisodeMapping.durationCompatible(115L * 60, 120)
        )
    }

    @Test
    fun unknownRuntimeDoesNotProveCompatibility() {
        assertEquals(
            null,
            CinemanaEpisodeMapping.durationCompatible(null, 120)
        )
    }

    @Test
    fun missingEpisodeInAbsoluteSequenceDoesNotTriggerRemapping() {
        val source = (1..70).filter { it != 29 }.map {
            CinemanaEpisodeMapping.EpisodeRef(
                (400000 + it).toString(),
                if (it <= 35) 1 else 2,
                it,
                "Episode $it"
            )
        }

        assertEquals(
            source,
            CinemanaEpisodeMapping.normalizeContinuous(source, counts)
        )
    }

    @Test
    fun released71EpisodesStayPlayableWhenTmdbHas80() {
        val officialCounts = mapOf(1 to 28, 2 to 35, 3 to 17)

        val source = (1..71).map { number ->
            CinemanaEpisodeMapping.EpisodeRef(
                videoId = (500000 + number).toString(),
                season = if (number <= 35) 1 else 2,
                episode = number,
                title = "Episode $number"
            )
        }

        val result = CinemanaEpisodeMapping.normalizeContinuous(
            source, officialCounts
        )

        assertEquals(71, result.size)
        assertEquals(28, result.count { it.season == 1 })
        assertEquals(35, result.count { it.season == 2 })
        assertEquals(8, result.count { it.season == 3 })
        assertEquals(source.map { it.videoId }, result.map { it.videoId })
        assertEquals(3 to 8, result.last().let { it.season to it.episode })
    }

    @Test
    fun resetting63Plus8EpisodesMapToThreeOfficialSeasons() {
        val source = (1..63).map {
            CinemanaEpisodeMapping.EpisodeRef(
                (600000 + it).toString(), 1, it, "Episode $it"
            )
        } + (1..8).map {
            CinemanaEpisodeMapping.EpisodeRef(
                (700000 + it).toString(), 2, it, "Episode $it"
            )
        }

        val mapped = CinemanaEpisodeMapping.normalizeContinuous(
            source, mapOf(1 to 28, 2 to 35, 3 to 17)
        )

        assertEquals(71, mapped.size)
        assertEquals(28, mapped.count { it.season == 1 })
        assertEquals(35, mapped.count { it.season == 2 })
        assertEquals(8, mapped.count { it.season == 3 })
        assertEquals(source.map { it.videoId }, mapped.map { it.videoId })
        assertEquals(3 to 8, mapped.last().let { it.season to it.episode })
    }

    @Test
    fun shortEditionWith350EpisodesMustKeepNativeNumbering() {
        val source = (1..350).map {
            CinemanaEpisodeMapping.EpisodeRef(
                (800000 + it).toString(),
                when {
                    it <= 280 -> 1
                    it <= 315 -> 2
                    else -> 3
                },
                it,
                "Episode $it"
            )
        }

        assertEquals(
            source,
            CinemanaEpisodeMapping.normalizeContinuous(
                source, mapOf(1 to 28, 2 to 35, 3 to 17)
            )
        )
    }

    @Test
    fun cinematanaNumericRuntimeUsesPlausibleSeconds() {
        assertEquals(6759L, CinemanaEpisodeMapping.numericCinemanaSeconds(6759.6))
        assertEquals(1103L, CinemanaEpisodeMapping.numericCinemanaSeconds(1103.85))
        assertEquals(2409L, CinemanaEpisodeMapping.numericCinemanaSeconds(2409.0))
    }

    @Test
    fun invalidNumericRuntimeRemainsUnknown() {
        assertEquals(null, CinemanaEpisodeMapping.numericCinemanaSeconds(0.0))
        assertEquals(null, CinemanaEpisodeMapping.numericCinemanaSeconds(42.0))
        assertEquals(null, CinemanaEpisodeMapping.numericCinemanaSeconds(Double.NaN))
    }

    @Test
    fun mixedShortAndLongSeasonsRejectExternalMapping() {
        assertEquals(
            false,
            CinemanaEpisodeMapping.durationSamplesCompatible(
                listOf(2409L, 8245L, 8392L), 135
            )
        )
    }

    @Test
    fun consistentLongEpisodesAllowOfficialMapping() {
        assertEquals(
            true,
            CinemanaEpisodeMapping.durationSamplesCompatible(
                listOf(6759L, 7827L, 7500L), 120
            )
        )
    }

    @Test
    fun missingRuntimeSampleNeverProvesCompatibility() {
        assertEquals(
            null,
            CinemanaEpisodeMapping.durationSamplesCompatible(
                listOf(7200L, null, 7500L), 120
            )
        )
    }
}