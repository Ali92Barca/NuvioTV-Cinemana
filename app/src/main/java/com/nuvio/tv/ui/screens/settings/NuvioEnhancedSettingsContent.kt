@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.builtincache.NuvioCacheKind
import com.nuvio.tv.data.builtincache.NuvioCachePolicy
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
internal fun NuvioEnhancedSettingsContent(
    initialFocusRequester: FocusRequester?,
    playback: PlaybackSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val policy = remember(context) { NuvioCachePolicy(context.applicationContext) }
    val player by playback.playerSettings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var conservative by remember { mutableStateOf(policy.conservativeRam) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        item {
            SettingsDetailHeader(
                title = stringResource(R.string.nuvio_enhanced_title),
                subtitle = stringResource(R.string.nuvio_enhanced_subtitle)
            )
        }
        item {
            CachePreference(NuvioCacheKind.POSTERS, policy, stringResource(R.string.nuvio_poster_cache),
                if (initialFocusRequester == null) Modifier else Modifier.focusRequester(initialFocusRequester))
        }
        item {
            CachePreference(NuvioCacheKind.DETAILS, policy, stringResource(R.string.nuvio_details_cache))
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.nuvio_conservative_ram),
                subtitle = stringResource(R.string.nuvio_conservative_ram_subtitle),
                checked = conservative,
                onToggle = {
                    conservative = !conservative
                    policy.conservativeRam = conservative
                }
            )
        }
        item { BuiltinPosterLanguagePreference() }
        item { DownloadLocationPreference() }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.provider_scene_skip_title),
                subtitle = stringResource(R.string.provider_scene_skip_subtitle),
                checked = player?.providerSceneSkipEnabled == true,
                enabled = player?.playerPreference != null && player?.playerPreference != PlayerPreference.EXTERNAL,
                onToggle = {
                    val current = player ?: return@SettingsToggleRow
                    scope.launch { playback.setProviderSceneSkipEnabled(!current.providerSceneSkipEnabled) }
                }
            )
        }
    }
}

@Composable
private fun CachePreference(kind: NuvioCacheKind, policy: NuvioCachePolicy, title: String, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(policy.customMb(kind)) }
    var open by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(value == null) }
    var size by remember { mutableIntStateOf(value ?: 512) }
    SettingsActionRow(
        title = title,
        subtitle = stringResource(R.string.nuvio_cache_subtitle),
        value = value?.let { stringResource(R.string.nuvio_cache_mb, it) } ?: stringResource(R.string.nuvio_cache_auto),
        modifier = modifier,
        onClick = {
            automatic = value == null
            size = value ?: 512
            open = true
        }
    )
    if (open) NuvioDialog(title = title, onDismiss = { open = false }) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsToggleRow(
                title = stringResource(R.string.nuvio_cache_auto),
                subtitle = null,
                checked = automatic,
                onToggle = { automatic = !automatic }
            )
            if (!automatic) {
                val move: (Int) -> Unit = { delta ->
                    size = (size + delta * kind.step).coerceIn(kind.minimum, kind.maximum)
                }
                Button(
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        val direction = when (key.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> -1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        if (key.action == KeyEvent.ACTION_DOWN) move(direction)
                        true
                    },
                    onClick = { move(1) }
                ) { Text("◀  " + stringResource(R.string.nuvio_cache_mb, size) + "  ▶") }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (size - kind.minimum).toFloat() / (kind.maximum - kind.minimum) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row {
                Button(onClick = {
                    value = if (automatic) null else size.coerceIn(kind.minimum, kind.maximum)
                    policy.setCustomMb(kind, value)
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }
}
