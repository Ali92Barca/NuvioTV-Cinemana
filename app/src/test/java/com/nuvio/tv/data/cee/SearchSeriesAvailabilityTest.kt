package com.nuvio.tv.data.cee

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSeriesAvailabilityTest {
    private val checker = SearchSeriesAvailability(OkHttpClient())

    private fun preview(id: String, title: String = "Series", year: String? = "2026") = MetaPreview(
        id = id, type = ContentType.SERIES, name = title, poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = year, imdbRating = null, genres = emptyList()
    )

    @Test fun sameIdTitleAndYearAreRequired() {
        val native = preview("cinemana-series:123")
        assertTrue(checker.sameSeries(native, preview("cee:123")))
        assertFalse(checker.sameSeries(native, preview("cee:124")))
        assertFalse(checker.sameSeries(native, preview("cee:123", year = "2025")))
        assertFalse(checker.sameSeries(native, preview("cee:123", year = null)))
        assertFalse(checker.sameSeries(native, preview("cee:123", title = "Another series")))
    }

    @Test fun differentEditionsStayVisible() {
        assertFalse(checker.sameSeries(
            preview("cinemana-series:123", "Series Dubbed"),
            preview("cee:123", "Series Subtitled")
        ))
    }
}
