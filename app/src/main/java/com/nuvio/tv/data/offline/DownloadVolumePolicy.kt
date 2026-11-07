package com.nuvio.tv.data.offline

/** This chooses an effective volume without changing the stored preference. */
internal fun effectiveDownloadVolume(preferred: String, available: List<String>): String? =
    preferred.takeIf { it in available } ?: "internal".takeIf { it in available }
