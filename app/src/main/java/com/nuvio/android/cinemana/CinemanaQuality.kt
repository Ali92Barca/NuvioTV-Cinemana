package com.nuvio.android.cinemana

import java.util.Locale

/** Numeric heights, not alphabetical order (which puts 1080 before 720). */
object CinemanaQuality {
    fun height(label: String): Int {
        val text = label.lowercase(Locale.ROOT)
        Regex("(\\d{3,5})\\s*[x×]\\s*(\\d{3,5})").find(text)?.let {
            return minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("(?<![0-9])([0-9]{3,4})\\s*[pi](?![a-z])").find(text)?.let { return it.groupValues[1].toInt() }
        return when {
            Regex("\\b8k\\b").containsMatchIn(text) -> 4320
            Regex("\\b4k\\b|uhd|ultra[ -]?hd").containsMatchIn(text) -> 2160
            Regex("\\b2k\\b|qhd").containsMatchIn(text) -> 1440
            Regex("fhd|full[ -]?hd").containsMatchIn(text) -> 1080
            Regex("\\bhd\\b").containsMatchIn(text) -> 720
            Regex("\\bsd\\b").containsMatchIn(text) -> 480
            text.trim().matches(Regex("[0-9]{3,4}")) -> text.trim().toInt()
            else -> 0
        }
    }
}
