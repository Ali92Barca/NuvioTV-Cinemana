package com.nuvio.android.cinemana

import org.json.JSONObject

object CinemanaEditions {
    private val dubbed = Regex("مدبلج|مدبلجة|dubbed|dublaj", RegexOption.IGNORE_CASE)
    private val subtitled = Regex("مترجم|مترجمة|subtitled", RegexOption.IGNORE_CASE)

    fun label(titles: List<String>): String {
        val text = titles.joinToString(" ")
        return when {
            dubbed.containsMatchIn(text) && subtitled.containsMatchIn(text) -> "نسخة Cinemana"
            dubbed.containsMatchIn(text) -> "مدبلجة"
            subtitled.containsMatchIn(text) -> "مترجمة"
            else -> "نسخة Cinemana"
        }
    }

    fun explicit(titles: List<String>) = label(titles) != "نسخة Cinemana"

    fun identityTitle(title: String): String = title.replace(
        Regex("\\s*(?:[-–|:]|\\(|\\[)?\\s*(?:arabic\\s+)?(?:مدبلج(?:ة)?|مترجم(?:ة)?|dubbed|subtitled|dublaj)(?:\\s+(?:بالعربية|عربي|arabic|version))*\\s*[\\])]?[ \\t]*$", RegexOption.IGNORE_CASE), ""
    ).trim()

    /** Generic numeric duration fields have undocumented units, so don't guess. */
    fun duration(record: JSONObject): String? {
        for (field in listOf("durationSeconds", "videoDurationSeconds", "duration_seconds")) {
            val seconds = record.optDouble(field, 0.0).toLong()
            if (seconds > 0) return clock(seconds)
        }
        val millis = record.optDouble("duration_ms", 0.0).toLong()
        if (millis > 0) return clock(millis / 1000)
        val minutes = record.optDouble("durationMinutes", 0.0)
        if (minutes > 0) return clock((minutes * 60).toLong())
        for (field in listOf("duration", "videoDuration", "video_duration", "runtime")) {
            val text = record.optString(field).trim()
            if (text.matches(Regex("\\d{1,3}:\\d{2}(?::\\d{2})?"))) {
                val parts = text.split(':').map { it.toLong() }
                if (parts.drop(1).any { it > 59 }) continue
                val seconds = if (parts.size == 3) parts[0] * 3600 + parts[1] * 60 + parts[2] else parts[0] * 60 + parts[1]
                if (seconds > 0) return clock(seconds)
            }
        }
        return null
    }

    private fun clock(seconds: Long): String = if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
