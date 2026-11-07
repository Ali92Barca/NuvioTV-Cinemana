@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun TmdbApiKeyDialog(currentValue: String, onSave: (String) -> Unit, onDismiss: () -> Unit, onQrRequested: (() -> Unit)? = null) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    NuvioDialog(
        title = stringResource(R.string.tmdb_api_key_title),
        subtitle = stringResource(R.string.tmdb_api_key_description),
        onDismiss = onDismiss,
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { androidx.compose.material3.Text(stringResource(R.string.tmdb_api_key_title)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { onSave(value.trim()) }) { Text(stringResource(R.string.action_save)) }
            Button(onClick = { onSave("") }) { Text(stringResource(R.string.tmdb_api_key_reset)) }
            if (onQrRequested != null) {
                Button(onClick = onQrRequested) { Text(stringResource(R.string.tmdb_api_key_qr)) }
            }
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
