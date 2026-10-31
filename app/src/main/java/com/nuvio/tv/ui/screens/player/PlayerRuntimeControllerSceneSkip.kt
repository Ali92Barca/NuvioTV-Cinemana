package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.domain.model.ProviderSceneSkip
import com.nuvio.tv.domain.model.ProviderSceneSkipSession
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.bindProviderSceneSkip(raw: String?, addon: String?) {
    val data = ProviderSceneSkip.fromPayload(raw, addon)
        ?: StreamSceneSkipSidecar.forUrl(currentStreamUrl, addon)
    val previous = providerSceneSession?.data
    if (previous?.provider != data?.provider || previous?.videoId != data?.videoId) providerSceneSessionOverride = null
    providerSceneSession = data?.let(::ProviderSceneSkipSession)
    publishProviderSceneSkipState()
}

internal fun PlayerRuntimeController.publishProviderSceneSkipState() {
    val data = providerSceneSession?.data
    val enabled = providerSceneSessionOverride ?: providerSceneDefaultEnabled
    val playbackMs = currentPlaybackDurationMs()
    val matches = data?.matchesDuration(playbackMs) == true
    if (data != null && !matches) {
        android.util.Log.w("ProviderSceneSkip", "DurationMismatch provider=${data.provider} video=${data.videoId} sourceMs=${data.durationMs} playbackMs=$playbackMs differenceMs=${kotlin.math.abs(data.durationMs - playbackMs)}")
    }
    val old = _uiState.value
    if (old.providerSceneSource == data?.provider && old.providerSceneCount == (data?.ranges?.size ?: 0) &&
        old.providerSceneSkipEnabled == enabled && old.providerSceneDurationMatches == matches) return
    if (data != null && (old.providerSceneSource != data.provider || old.providerSceneDurationMatches != matches)) {
        android.util.Log.d("ProviderSceneSkip", "Ready provider=${data.provider} video=${data.videoId} scenes=${data.ranges.size} sourceDurationMs=${data.durationMs} playbackDurationMs=${currentPlaybackDurationMs()} match=$matches")
    }
    _uiState.update { it.copy(providerSceneSource = data?.provider, providerSceneCount = data?.ranges?.size ?: 0,
        providerSceneSkipEnabled = enabled, providerSceneDurationMatches = matches) }
}

internal fun PlayerRuntimeController.toggleProviderSceneSkipping() {
    providerSceneSessionOverride = !(providerSceneSessionOverride ?: providerSceneDefaultEnabled)
    publishProviderSceneSkipState()
}

internal fun PlayerRuntimeController.maybeSkipProviderScene(positionMs: Long) {
    publishProviderSceneSkipState()
    if (_playbackTimeline.value.isLive || !_uiState.value.isPlaying) return
    val session = providerSceneSession ?: return
    val target = session.nextSeek(positionMs, currentPlaybackDurationMs(),
        providerSceneSessionOverride ?: providerSceneDefaultEnabled, SystemClock.elapsedRealtime()) ?: return
    seekPlaybackTo(target, SeekParameters.EXACT)
    scheduleProgressSyncAfterSeek()
    android.util.Log.d("ProviderSceneSkip", "Skipped provider=${session.data.provider} video=${session.data.videoId} targetMs=$target")
}
