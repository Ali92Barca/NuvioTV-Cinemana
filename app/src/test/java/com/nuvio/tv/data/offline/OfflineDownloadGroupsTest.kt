package com.nuvio.tv.data.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadGroupsTest {
    private fun episode(key: String, show: String, season: Int, episode: Int, aliases: Set<String> = emptySet(), name: String = "Show") =
        OfflineItem(key, name, 1, emptyList(), OfflineMediaIdentity("series", show, "$show:$season:$episode", season, episode, aliases))

    @Test fun `show folder sorts seasons and episodes while films stay at root`() {
        val film = OfflineItem("film", "Movie", 1, emptyList(), OfflineMediaIdentity("movie", "tt90", "tt90", null, null))
        val groups = groupOfflineDownloads(listOf(episode("late", "tt1", 2, 1), film, episode("early", "tt1", 1, 4)))
        assertEquals(2, groups.size)
        val folder = groups.filterIsInstance<OfflineDownloadEntry.Series>().single()
        assertEquals(listOf("early", "late"), folder.episodes.map { it.key })
        assertEquals(film, groups.filterIsInstance<OfflineDownloadEntry.File>().single().item)
    }
    @Test fun `known aliases merge episodes from different catalogs including alias bridges`() {
        val groups = groupOfflineDownloads(listOf(
            episode("one", "cinemana-series:10", 1, 1),
            episode("two", "tt1", 1, 2, setOf("tmdb:tv:20")),
            episode("three", "cinemana-series:10", 1, 3, setOf("tmdb:20"))
        ))
        assertEquals(3, (groups.single() as OfflineDownloadEntry.Series).episodes.size)
    }
    @Test fun `identical display names do not merge unrelated shows and legacy files remain accessible`() {
        val legacy = OfflineItem("legacy", "Old file", 2, emptyList())
        val groups = groupOfflineDownloads(listOf(episode("one", "tt1", 1, 1), episode("two", "tt2", 1, 1), legacy))
        assertEquals(2, groups.filterIsInstance<OfflineDownloadEntry.Series>().size)
        assertEquals(legacy, groups.filterIsInstance<OfflineDownloadEntry.File>().single().item)
    }
    @Test fun `saved display name survives persistence without changing bookmark keys`() {
        val identity = OfflineMediaIdentity("series", "tt1", "tt1:3:7", 3, 7, displayName = "Series title")
        assertEquals(identity, OfflineMediaIdentity.fromJson(identity.toJson()))
        assertEquals(identity.keys(), identity.copy(displayName = "Localized title").keys())
    }
}
