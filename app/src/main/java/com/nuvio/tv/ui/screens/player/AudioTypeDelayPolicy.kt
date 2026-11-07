package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/** Track evidence only: codec families, channel counts and stream filenames do not prove Atmos. */
internal fun isAtmosAudioFormat(mimeType: String?, codecs: String?, label: String?): Boolean {
    val mime = mimeType.orEmpty().lowercase(java.util.Locale.ROOT)
    val codec = codecs.orEmpty().lowercase(java.util.Locale.ROOT)
    if (mime == "audio/eac3-joc" || codec.split(',').any {
            it.trim() in setOf("ec+3", "eac3-joc", "eac3_joc", "e-ac-3-joc")
        }) return true
    val name = label.orEmpty()
    val negated = Regex("(?i)\\b(?:no|non|without)[ -]*atmos\\b|\\batmos[ :=]*(?:false|off|no)\\b")
    return Regex("(?i)\\batmos\\b").containsMatchIn(name) && !negated.containsMatchIn(name)
}

internal fun selectedAudioTypeDelayMs(atmos: Boolean, normalMs: Int, atmosMs: Int): Int =
    (if (atmos) atmosMs.coerceIn(-500, 500) else normalMs.coerceIn(-3000, 3000))

internal fun PlayerRuntimeController.updateAudioTypeDelay(atmos: Boolean) {
    _uiState.value.let { state ->
        val effective = selectedAudioTypeDelayMs(atmos, state.normalAudioDelayMs, state.atmosAudioDelayMs)
        if (state.isAtmosAudio == atmos && audioDelayParts.typeMs == effective) return
        _uiState.update { it.copy(isAtmosAudio = atmos) }
        audioDelayParts = audioDelayParts.copy(typeMs = effective)
        applyEffectiveAudioDelay()
    }
}

internal suspend fun PlayerRuntimeController.loadAudioTypeDelays() {
    val (normal, atmos) = audioDelayRouteDataStore.loadTypeDelays(profileId)
    _uiState.update { it.copy(normalAudioDelayMs = normal, atmosAudioDelayMs = atmos) }
    // A new/rebuilt source starts conservatively until its selected track is known.
    updateAudioTypeDelay(false)
}
