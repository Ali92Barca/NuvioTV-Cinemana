@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.data.bingers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** Same action rows, dialogs and theme tokens as the other tracking providers. */
@Composable
fun BingersSettingsCard(viewModel: BingersSettingsViewModel = hiltViewModel()) {
    val service = viewModel.service
    val state by service.state.collectAsState()
    val pairing by viewModel.pairing.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    LaunchedEffect(service) { service.refresh() }
    DisposableEffect(showDialog) {
        if (showDialog && !state.linked) viewModel.startPairing()
        onDispose { viewModel.stopPairing() }
    }
    SettingsActionRow(
        title = "Bingers",
        subtitle = stringResource(if (state.linked) R.string.bingers_connected_subtitle else R.string.bingers_connect_subtitle),
        value = stringResource(if (state.linked) R.string.bingers_connected else R.string.bingers_disconnected),
        valueColor = if (state.linked) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
        leadingIcon = Icons.Default.Sync, leadingArtworkSize = 40.dp,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        NuvioDialog(title = "Bingers", width = 720.dp, onDismiss = { showDialog = false }) {
            if (state.linked) {
                Text(stringResource(R.string.bingers_link_success), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.Primary)
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                SettingsToggleRow(title = stringResource(R.string.bingers_sync_title),
                    subtitle = stringResource(R.string.bingers_sync_subtitle), checked = state.enabled,
                    onToggle = { service.setEnabled(!state.enabled) }, enabled = !state.busy)
                SettingsActionRow(title = stringResource(R.string.bingers_retry),
                    subtitle = stringResource(R.string.bingers_queue, state.pending, state.unmatched),
                    onClick = service::retry, enabled = !state.busy)
                SettingsActionRow(title = stringResource(R.string.bingers_disconnect), subtitle = null,
                    onClick = { service.disconnect(); showDialog = false }, enabled = !state.busy)
            } else {
                Text(stringResource(R.string.bingers_phone_instructions), color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val bitmap = remember(pairing.url) { pairing.url?.let { runCatching { QrCodeGenerator.generate(it, 420, 4) }.getOrNull() } }
                if (bitmap != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), stringResource(R.string.bingers_qr_description), modifier = Modifier.size(180.dp))
                }
                Text(stringResource(R.string.bingers_same_network), color = NuvioTheme.colors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                if (pairing.loading) Text(stringResource(R.string.bingers_preparing), color = NuvioTheme.colors.TextSecondary)
                pairing.error?.let { Text(it, color = NuvioTheme.colors.TextSecondary) }
                if (state.busy) Text(stringResource(R.string.bingers_linking), color = NuvioTheme.colors.Primary)
                else if (state.message != "غير مرتبط") Text(state.message, color = NuvioTheme.colors.TextSecondary)
                Button(onClick = viewModel::startPairing, enabled = !state.busy,
                    colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, contentColor = NuvioTheme.colors.TextPrimary)) {
                    Text(stringResource(R.string.bingers_new_qr))
                }
            }
            Button(onClick = { showDialog = false }, colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard, contentColor = NuvioTheme.colors.TextPrimary)) {
                Text(stringResource(R.string.bingers_close))
            }
        }
    }
}
