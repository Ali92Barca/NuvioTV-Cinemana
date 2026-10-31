package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.ProviderSceneSkip

internal object StreamSceneSkipSidecar {
    private data class Entry(val url: String, val addon: String, val data: ProviderSceneSkip)
    @Volatile private var entry: Entry? = null

    fun set(url: String?, raw: String?, addon: String?) {
        val data = ProviderSceneSkip.fromPayload(raw, addon)
        entry = if (url != null && (url.startsWith("https://") || url.startsWith("http://")) && addon != null && data != null)
            Entry(url, addon, data) else null
    }
    fun forUrl(url: String, addon: String?): ProviderSceneSkip? = entry?.takeIf {
        it.url == url && it.addon.equals(addon, true)
    }?.data
}
