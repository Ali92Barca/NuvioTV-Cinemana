@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun TmdbQrPairingDialog(
    state: TmdbSettingsViewModel.QrPairingState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = remember(state.url) {
        state.url?.let { url ->
            runCatching { QrCodeGenerator.generate(url, 420, margin = 2) }.getOrNull()
        }
    }

    NuvioDialog(
        title = "TMDB API key via QR",
        subtitle = "Scan with your phone on the same local network",
        onDismiss = onDismiss,
        width = 600.dp,
        suppressFirstKeyUp = false
    ) {
        when {
            state.pendingKey != null -> {
                Text("A TMDB key was received from your phone.")
                Text("Do you want to save it for the current profile?")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(onClick = onConfirm) {
                        Text("Save key")
                    }
                    Button(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }

            state.loading -> {
                Text("Starting the local pairing server...")
            }

            state.error != null -> {
                Text(state.error)
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }

            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR code to enter TMDB API key",
                    modifier = Modifier.size(250.dp)
                )
                Text("Scan the QR code, enter your TMDB v3 key, then send it.")
                Text("The pairing session expires after 5 minutes.")
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }

            else -> {
                Text("Unable to generate the QR code.")
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}