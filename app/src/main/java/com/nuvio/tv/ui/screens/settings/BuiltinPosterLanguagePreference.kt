@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LocalBuiltinCatalogPosters
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun BuiltinPosterLanguagePreference() {
    val posters = LocalBuiltinCatalogPosters.current ?: return
    val arabic by posters.arabic.collectAsState()
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = "لغة البوسترات",
        subtitle = "بوسترات BetterPosters لـCinemana وCee وأعمال IPTV المطابقة",
        value = if (arabic) "العربية" else "الإنكليزية",
        onClick = { open = true }
    )
    if (open) NuvioDialog(title = "لغة البوسترات", onDismiss = { open = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "العربية", false to "الإنكليزية").forEach { (value, label) ->
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    posters.setArabic(value)
                    open = false
                }) { Text(if (arabic == value) "✓ $label" else label) }
            }
        }
    }
}
