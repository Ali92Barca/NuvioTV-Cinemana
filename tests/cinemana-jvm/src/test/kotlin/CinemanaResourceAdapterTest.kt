package com.nuvio.android.cinemana

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CinemanaResourceAdapterTest {
    private val base = "https://cinemana.nuvio.invalid"
    private fun adapter(fetch: suspend (String) -> String) = CinemanaResourceAdapter(
        CinemanaClient(transport = fetch), { TmdbCredentials("test-key", "en") }, fetch)

    @Test fun turkishSearchRetriesAsciiSpellingAndKeepsStrictIdentity() = runBlocking {
        val queries = mutableListOf<String>()
        val provider = adapter { url ->
            when {
                url.contains("/tv/123?") -> """{"name":"المدينة البعيدة","original_name":"Uzak Şehir","first_air_date":"2024-11-11"}"""
                url.contains("AdvancedSearch") -> {
                    queries.add(url)
                    if (url.contains("videoTitle=Uzak+Sehir&"))
                        """[{"nb":"40","rootSeries":"40","en_title":"Uzak Sehir","year":"2024"}]"""
                    else "[]"
                }
                url.contains("videoSeason/id/40") -> """[{"nb":"42","season":1,"episodeNummer":2}]"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/far-away.mp4"}]"""
                else -> "{}"
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/series/tmdb%3Atv%3A123%3A1%3A2.json")!!).getJSONArray("streams")
        assertEquals(1, streams.length())
        assertTrue(queries.any { it.contains("videoTitle=Uzak+%C5%9Eehir&") })
        assertTrue(queries.any { it.contains("videoTitle=Uzak+Sehir&") })
    }

    @Test fun streamsCarrySubtitleFilesForTheirExactVideoEdition() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("allVideoInfo/id/42") -> """{"en_title":"Example Subtitled","translations":[{"type":"ar","extention":"vtt","file":"https://cdn.example/edition42-ar.vtt"}]}"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/edition42.mp4"}]"""
                else -> error("Unexpected request $url")
            }
        }
        val stream = JSONObject(provider.handle("$base/stream/series/cinemana%3A42.json")!!)
            .getJSONArray("streams").getJSONObject(0)
        val subtitle = stream.getJSONArray("subtitles").getJSONObject(0)
        assertEquals("https://cdn.example/edition42-ar.vtt", subtitle.getString("url"))
        assertEquals("ara", subtitle.getString("lang"))
        assertTrue(subtitle.getString("name").contains("مترجمة"))
    }

    @Test fun missingSubtitleMetadataDoesNotHidePlayableVideo() = runBlocking {
        val provider = adapter { url ->
            if (url.contains("transcoddedFiles")) """[{"videoUrl":"https://cdn.example/video.mp4"}]"""
            else throw IllegalStateException("Subtitle info unavailable")
        }
        val stream = JSONObject(provider.handle("$base/stream/movie/cinemana%3A42.json")!!)
            .getJSONArray("streams").getJSONObject(0)
        assertEquals(0, stream.getJSONArray("subtitles").length())
    }

    @Test fun manifestAdvertisesMoviesSeriesAndExternalStreams() {
        val manifest = adapter { error("No network expected") }.manifest()
        assertEquals(32, manifest.getJSONArray("catalogs").length())
        val resources = manifest.getJSONArray("resources").toString()
        assertTrue(resources.contains("tmdb:"))
        assertTrue(resources.contains("subtitles"))
    }

    @Test fun neverInterceptsOtherHosts() = runBlocking {
        val provider = adapter { error("No network expected") }
        assertNull(provider.handle("https://example.org/a b"))
        assertNull(provider.handle("https://cinemana.nuvio.invalid.attacker.org/manifest.json"))
    }

    @Test fun preservesSearchEscapingAndStopsRepeatedPages() = runBlocking {
        var calls = 0
        val provider = adapter { url ->
            calls++
            assertTrue(url.contains("videoTitle=Tom+%26+Jerry"))
            """[{"nb":"42","en_title":"Tom & Jerry","year":"2021"}]"""
        }
        val response = JSONObject(provider.handle("$base/catalog/movie/cinemana_search_movie/search=Tom%20%26%20Jerry.json")!!)
        assertEquals("cinemana:42", response.getJSONArray("metas").getJSONObject(0).getString("id"))
        assertEquals(2, calls)
    }

    @Test fun paginationUsesOpaqueApiCursorsAndSeriesCategory() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = adapter { url ->
            calls.add(url)
            assertTrue(url.contains("videoKind=2"))
            assertTrue(url.contains("categoryID=97%2C84"))
            val start = if (url.contains("offset=500")) 31 else 1
            val rows = (start until start + 30).joinToString(",") { id ->
                """{"nb":"$id","rootSeries":"$id","en_title":"Example $id"}"""
            }
            """{"info":[$rows],"offset":500}"""
        }
        val response = JSONObject(provider.handle("$base/catalog/series/cinemana_action_series/skip=30.json")!!)
        assertEquals("cinemana-series:31", response.getJSONArray("metas").getJSONObject(0).getString("id"))
        assertEquals(2, calls.size)
        assertTrue(calls.last().contains("offset=500"))
    }

    @Test fun directPlaybackUsesCinemanaVideoIdAndFiltersBadUrls() = runBlocking {
        val provider = adapter { url ->
            if (url.contains("allVideoInfo")) return@adapter "{}"
            assertTrue(url.endsWith("transcoddedFiles/id/42"))
            """[{"videoUrl":"https://cdn.example/movie.mp4","resolution":"1080p"},{"videoUrl":"javascript:bad"}]"""
        }
        val streams = JSONObject(provider.handle("$base/stream/movie/cinemana%3A42.json")!!).getJSONArray("streams")
        assertEquals(1, streams.length())
        assertEquals("https://cdn.example/movie.mp4", streams.getJSONObject(0).getString("url"))
    }

    @Test fun subtitlesPreferVttAndKeepFallback() = runBlocking {
        val provider = adapter {
            """{"nb":"42","translations":[{"type":"ar","extention":"srt","file":"https://cdn.example/ar.srt"},{"type":"ar","extention":"vtt","file":"https://cdn.example/ar.vtt"}],"enTranslationFilePath":"https://cdn.example/en.srt"}"""
        }
        val subtitles = JSONObject(provider.handle("$base/subtitles/movie/cinemana%3A42.json")!!).getJSONArray("subtitles")
        assertEquals(2, subtitles.length())
        assertEquals("https://cdn.example/ar.vtt", subtitles.getJSONObject(0).getString("url"))
    }

    @Test fun externalSeriesMatchesSeasonEpisodeInsteadOfFirstEpisode() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("/tv/123?") -> """{"name":"Example","original_name":"Example","first_air_date":"2020-01-01"}"""
                url.contains("AdvancedSearch") -> """[{"nb":"40","rootSeries":"40","en_title":"Example","year":"2020"}]"""
                url.contains("videoSeason/id/40") -> """[{"nb":"41","season":1,"episodeNummer":1},{"nb":"42","season":1,"episodeNummer":2}]"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/episode2.mp4"}]"""
                else -> error("Unexpected request $url")
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/series/tmdb%3Atv%3A123%3A1%3A2.json")!!).getJSONArray("streams")
        assertEquals("https://cdn.example/episode2.mp4", streams.getJSONObject(0).getString("url"))
    }

    @Test fun refusesSeriesWithoutEpisodeAndAmbiguousMovies() = runBlocking {
        val noCalls = adapter { error("Must not guess first episode") }
        val streams = JSONObject(noCalls.handle("$base/stream/series/tt123.json")!!).getJSONArray("streams")
        assertEquals(0, streams.length())
        val ambiguous = adapter { url ->
            if (url.contains("/movie/123?")) """{"title":"Example","release_date":"2020-01-01"}"""
            else """[{"nb":"41","en_title":"Example","year":"2020"},{"nb":"42","en_title":"Example","year":"2020"}]"""
        }
        assertEquals(0, JSONObject(ambiguous.handle("$base/stream/movie/tmdb%3A123.json")!!).getJSONArray("streams").length())
    }

    @Test fun cancellationIsNeverSwallowedByEpisodeTraversal() {
        assertFailsWith<CancellationException> {
            runBlocking { CinemanaClient(transport = { throw CancellationException() }).episodes("42") }
        }
    }

    @Test fun episodeMetadataCannotReplacePlaybackIds() {
        val merged = CinemanaEpisodeMapping.merge(
            listOf(CinemanaEpisodeMapping.EpisodeRef("42", 1, 2, "old")),
            listOf(CinemanaEpisodeMapping.EpisodeRef("43", 2, 1, "next")),
            listOf(CinemanaEpisodeMapping.TmdbEpisode(1, 2, "Official")))
        assertEquals(listOf("42", "43"), merged.map { it.videoId })
        assertEquals("Official", merged.first().title)
    }
    @Test fun rootlessSearchAddsMissingEpisodesWithoutOtherShows() = runBlocking {
        val client = CinemanaClient(transport = { url ->
            when {
                url.contains("videoSeason") -> """[{"nb":"42","season":1,"episodeNummer":1}]"""
                url.contains("AdvancedSearch") -> """[{"nb":"43","en_title":"Example","year":"2020","rootSeries":"0","episodeNummer":2},{"nb":"44","en_title":"Example","year":"2021","episodeNummer":3},{"nb":"45","en_title":"Example","year":"2020","rootSeries":"999","episodeNummer":4}]"""
                else -> error("Unexpected request")
            }
        })
        val result = client.completeEpisodes("42", JSONObject("""{"en_title":"Example","year":"2020"}"""))
        assertEquals(listOf("42", "43"), result.map { it.videoId })
    }

    @Test fun nativeMetadataSurvivesTmdbFailure() = runBlocking {
        val provider = adapter { url ->
            if (url.contains("allVideoInfo")) """{"nb":"42","en_title":"Example","en_content":"Local description","year":"2020"}"""
            else error("TMDB unavailable")
        }
        val meta = JSONObject(provider.handle("$base/meta/movie/cinemana%3A42.json")!!).getJSONObject("meta")
        assertEquals("cinemana:42", meta.getString("id"))
        assertEquals("Local description", meta.getString("description"))
    }

    @Test fun continuousNumberingPreservesVideoIdentity() {
        val episodes = listOf(CinemanaEpisodeMapping.EpisodeRef("42", 1, 13, "Episode 13"))
        val normalized = CinemanaEpisodeMapping.normalizeContinuous(episodes, mapOf(1 to 12, 2 to 12))
        assertEquals("42", normalized.single().videoId)
        assertEquals(2, normalized.single().season)
        assertEquals(1, normalized.single().episode)
        assertEquals(episodes, CinemanaEpisodeMapping.normalizeContinuous(episodes, mapOf(1 to 12, 3 to 12)))
    }

    @Test fun matchingRejectsPartialTitlesAndWrongYear() = runBlocking {
        val matcher = CinemanaTmdbMatcher("key", fetch = {
            """{"results":[{"id":123,"title":"Example Extra","release_date":"2020-01-01","popularity":99}]}"""
        })
        assertNull(matcher.match("Example", 2020, false))
        val wrongYear = CinemanaTmdbMatcher("key", fetch = {
            """{"results":[{"id":123,"title":"Example","release_date":"1980-01-01"}]}"""
        })
        assertNull(wrongYear.match("Example", 2020, false))
    }

    @Test fun streamQualitiesAreHighestFirst() = runBlocking {
        val provider = adapter {
            """[{"videoUrl":"https://cdn.example/480","resolution":"480p"},{"videoUrl":"https://cdn.example/720","resolution":"HD"},{"videoUrl":"https://cdn.example/1080","resolution":"1920x1080"},{"videoUrl":"https://cdn.example/2160","resolution":"4K"},{"videoUrl":"https://cdn.example/unknown","resolution":"Original"}]"""
        }
        val streams = JSONObject(provider.handle("$base/stream/movie/cinemana%3A42.json")!!).getJSONArray("streams")
        assertEquals(listOf("2160", "1080", "720", "480", "unknown"),
            (0 until streams.length()).map { streams.getJSONObject(it).getString("url").substringAfterLast('/') })
        assertEquals(1080, CinemanaQuality.height("1080p MP4"))
        assertEquals(2160, CinemanaQuality.height("UHD"))
    }

    // Synthetic API fixtures using the reported titles; these are not live Cinemana responses.
    @Test fun metadataMatchesArabicAliasWhenEnglishTitleDiffers() = runBlocking {
        val matcher = CinemanaTmdbMatcher("key", "ar-SA", fetch = {
            """{"results":[{"id":123,"name":"القضاء","original_name":"Yargı","first_air_date":"2021-01-01"}]}"""
        })
        assertEquals(123, matcher.match(listOf("Family Secrets", "القضاء"), 2021, true)?.tmdbId)
    }

    @Test fun metadataFallsBackToEnglishSearchLocale() = runBlocking {
        val matcher = CinemanaTmdbMatcher("key", "ar-SA", fetch = { url ->
            if (url.contains("language=en-US"))
                """{"results":[{"id":123,"name":"Far Away","original_name":"Uzak Şehir","first_air_date":"2024-01-01"}]}"""
            else """{"results":[{"id":123,"name":"المدينة البعيدة","original_name":"Uzak Şehir","first_air_date":"2024-01-01"}]}"""
        })
        assertEquals(123, matcher.match("Far Away", 2024, true)?.tmdbId)
    }

    @Test fun metadataAcceptsSeasonYearOnlyWithinVerifiedAirDates() = runBlocking {
        val matcher = CinemanaTmdbMatcher("key", fetch = { url ->
            if (url.contains("/search/"))
                """{"results":[{"id":123,"name":"Yargi","original_name":"Yargı","first_air_date":"2021-01-01"}]}"""
            else """{"first_air_date":"2021-01-01","last_air_date":"2024-06-01"}"""
        })
        assertEquals(123, matcher.match("Yargi", 2023, true)?.tmdbId)
        assertNull(matcher.match("Yargi", 2026, true))
    }

    @Test fun externalCatalogUsesLaterSeasonYearAndAvoidsSlowExpansion() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("/tv/123?") -> """{"name":"المدينة البعيدة","original_name":"Uzak Şehir","first_air_date":"2024-11-11","last_air_date":"2026-10-26"}"""
                url.contains("/translations") || url.contains("/alternative_titles") -> "{}"
                url.contains("AdvancedSearch") -> """[{"nb":"40","rootSeries":"40","ar_title":"المدينة البعيدة","en_title":"Far Away","year":"2025"}]"""
                url.contains("videoSeason/id/40") -> """[{"nb":"42","season":2,"episodeNummer":1}]"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/s2e1.mp4","resolution":"1080p"}]"""
                else -> error("Unexpected slow expansion or wrong ID: $url")
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/series/tmdb%3A123%3A2%3A1.json")!!).getJSONArray("streams")
        assertEquals(1, streams.length())
        assertEquals("https://cdn.example/s2e1.mp4", streams.getJSONObject(0).getString("url"))
    }

    @Test fun searchDoesNotStopOnAnotherPageOfSameSeriesEpisodes() = runBlocking {
        val client = CinemanaClient(transport = { url ->
            val page = url.substringAfter("&page=").substringBefore('&').toInt()
            when (page) {
                0 -> """[{"nb":"41","rootSeries":"40","en_title":"First"}]"""
                1 -> """[{"nb":"42","rootSeries":"40","en_title":"First"}]"""
                2 -> """[{"nb":"51","rootSeries":"50","en_title":"Second"}]"""
                else -> "[]"
            }
        })
        assertEquals(listOf("40", "50"), client.search("Example", true).map { it.id })
    }

    @Test fun parentShowIdentityIsUsedOnlyForCinemanaRequests() {
        assertEquals("tmdb:123:2:1", CinemanaRequestIdentity.streamId("org.nuvio.cinemana.builtin", "series", "tt999", "tmdb:123", 2, 1))
        assertEquals("tt999", CinemanaRequestIdentity.streamId("another.addon", "series", "tt999", "tmdb:123", 2, 1))
        assertEquals("cinemana:42", CinemanaRequestIdentity.streamId("org.nuvio.cinemana.builtin", "series", "cinemana:42", "tmdb:123", 2, 1))
    }

    @Test fun overlappingShowsWithSameTitlesRemainAmbiguous() = runBlocking {
        val matcher = CinemanaTmdbMatcher("key", fetch = {
            """{"results":[{"id":123,"name":"Example","first_air_date":"2021-01-01"},{"id":124,"name":"Example","first_air_date":"2021-01-01"}]}"""
        })
        assertNull(matcher.match("Example", 2021, true))
    }

    @Test fun externalSeriesReturnsSubtitledAndDubbedInsteadOfRejectingBoth() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("/tv/123?") -> """{"name":"القضاء","original_name":"Yargı","first_air_date":"2021-01-01","last_air_date":"2024-06-01"}"""
                url.contains("/translations") || url.contains("/alternative_titles") -> "{}"
                url.contains("AdvancedSearch") -> """[{"nb":"40","rootSeries":"40","ar_title":"القضاء (مترجم)","en_title":"Yargi Subtitled","year":"2021"},{"nb":"50","rootSeries":"50","ar_title":"القضاء - مدبلج","en_title":"Yargi Dubbed","year":"2021"}]"""
                url.contains("videoSeason/id/40") -> """[{"nb":"42","season":1,"episodeNummer":2}]"""
                url.contains("videoSeason/id/50") -> """[{"nb":"52","season":1,"episodeNummer":2}]"""
                url.contains("allVideoInfo/id/42") -> """{"ar_title":"القضاء (مترجم)","duration":"02:10:30"}"""
                url.contains("allVideoInfo/id/52") -> """{"ar_title":"القضاء - مدبلج","durationSeconds":2700}"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/sub720","resolution":"720p"},{"videoUrl":"https://cdn.example/sub1080","resolution":"1080p"}]"""
                url.contains("transcoddedFiles/id/52") -> """[{"videoUrl":"https://cdn.example/dub1080","resolution":"1080p"}]"""
                else -> error("Unexpected fixture request: $url")
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/series/tmdb%3A123%3A1%3A2.json")!!).getJSONArray("streams")
        assertEquals(3, streams.length())
        val titles = (0 until streams.length()).map { streams.getJSONObject(it).getString("title") }
        assertTrue(titles.any { it.contains("مترجمة") && it.contains("2:10:30") })
        assertTrue(titles.any { it.contains("مدبلجة") && it.contains("45:00") })
        assertTrue(titles.last().contains("720p"))
    }

    @Test fun editionNamesDoNotChangeWorkIdentity() {
        for (title in listOf("القضاء (مترجم)", "القضاء - مدبلج", "القضاء مدبلج بالعربية"))
            assertEquals("القضاء", CinemanaEditions.identityTitle(title))
        assertEquals("Yargi", CinemanaEditions.identityTitle("Yargi [Arabic Dubbed]"))
        assertEquals("نسخة Cinemana", CinemanaEditions.label(listOf("An Arabic original")))
    }

    @Test fun durationUsesExplicitUnitsAndDoesNotInventUnknownLengths() {
        assertEquals("1:30:00", CinemanaEditions.duration(JSONObject("""{"durationSeconds":5400}""")))
        assertEquals("45:30", CinemanaEditions.duration(JSONObject("""{"duration":"45:30"}""")))
        assertNull(CinemanaEditions.duration(JSONObject("""{"duration":5400}""")))
        assertNull(CinemanaEditions.duration(JSONObject("""{"duration":"01:99:00"}""")))
    }

    @Test fun failingEditionDoesNotHideWorkingEdition() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("/movie/123?") -> """{"title":"Example","release_date":"2020-01-01"}"""
                url.contains("/translations") || url.contains("/alternative_titles") -> "{}"
                url.contains("AdvancedSearch") -> """[{"nb":"41","en_title":"Example Subtitled","year":"2020"},{"nb":"42","en_title":"Example Dubbed","year":"2020"}]"""
                url.contains("allVideoInfo") -> "{}"
                url.contains("transcoddedFiles/id/41") -> """[{"videoUrl":"https://cdn.example/good","resolution":"1080p"}]"""
                else -> error("Broken dubbed edition")
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/movie/tmdb%3A123.json")!!).getJSONArray("streams")
        assertEquals(1, streams.length())
    }

    @Test fun twoEditionsUnderOneSeriesRootAreBothReturned() = runBlocking {
        val provider = adapter { url ->
            when {
                url.contains("/tv/123?") -> """{"name":"Example","first_air_date":"2020-01-01"}"""
                url.contains("/translations") || url.contains("/alternative_titles") -> "{}"
                url.contains("AdvancedSearch") -> """[{"nb":"40","rootSeries":"40","en_title":"Example","year":"2020"}]"""
                url.contains("videoSeason/id/40") -> """[{"nb":"41","season":1,"episodeNummer":2,"en_title":"Example Subtitled"},{"nb":"42","season":1,"episodeNummer":2,"en_title":"Example Dubbed"}]"""
                url.contains("allVideoInfo/id/41") -> """{"en_title":"Example Subtitled"}"""
                url.contains("allVideoInfo/id/42") -> """{"en_title":"Example Dubbed"}"""
                url.contains("transcoddedFiles/id/41") -> """[{"videoUrl":"https://cdn.example/sub","resolution":"1080p"}]"""
                url.contains("transcoddedFiles/id/42") -> """[{"videoUrl":"https://cdn.example/dub","resolution":"1080p"}]"""
                else -> error("Unexpected request")
            }
        }
        val streams = JSONObject(provider.handle("$base/stream/series/tmdb%3A123%3A1%3A2.json")!!).getJSONArray("streams")
        assertEquals(2, streams.length())
        assertTrue(streams.toString().contains("مترجمة"))
        assertTrue(streams.toString().contains("مدبلجة"))
    }

    @Test fun rootlessEditionGroupingUsesArabicEditionLabelsToo() = runBlocking {
        val client = CinemanaClient(transport = {
            """[{"nb":"41","rootSeries":"0","en_title":"Example","ar_title":"مثال مدبلج","year":"2020"},{"nb":"42","rootSeries":"0","en_title":"Example","ar_title":"مثال مترجم","year":"2020"}]"""
        })
        assertEquals(2, client.search("Example", true).size)
    }

}
