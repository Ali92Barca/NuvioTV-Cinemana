package com.nuvio.tv.data.offline

import com.nuvio.tv.domain.model.Stream
import java.net.URI

internal fun supportsBuiltinDownload(stream: Stream): Boolean {
    val builtin = stream.addonName.contains("Cinemana", true) ||
        stream.addonName.equals("Cee", true)
    if (!builtin) return false
    val uri = try { URI(stream.url ?: return false) } catch (_: Exception) { return false }
    val path = uri.path.orEmpty().lowercase()
    return uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        !path.endsWith(".m3u8") && !path.endsWith(".mpd")
}
