@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.data.offline.OfflineDownloadLocation
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
internal fun DownloadLocationPreference() {
    val context = LocalContext.current
    val store = remember(context) { OfflineDownloadLocation(context) }
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<OfflineDownloadLocation.Location>()) }
    var selected by remember { mutableStateOf(store.selectedId()) }
    var show by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, show) {
        while (true) {
            try { choices = withContext(Dispatchers.IO) { store.locations() } }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.localizedMessage
            }
            delay(2000)
        }
    }
    val current = choices.firstOrNull { it.id == selected }
    SettingsActionRow(
        title = "مكان تنزيل الأفلام والمسلسلات",
        subtitle = current?.folder?.absolutePath ?: ("USB مفصول: الحفظ الجديد داخلي مؤقتاً، ويعود للخارجي عند توصيله.\n" +
            (choices.firstOrNull { it.id == "internal" }?.folder?.absolutePath ?: "ذاكرة الجهاز غير متاحة")),
        value = if (selected == "internal") "ذاكرة الجهاز" else if (current == null) "داخلي مؤقتاً — المفضّل USB" else "ذاكرة خارجية / USB",
        onClick = { show = true }
    )
    if (show) NuvioDialog(title = "مكان التنزيل", onDismiss = { if (!busy) show = false }) {
        Column(Modifier.widthIn(max = 560.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("يؤثر على التنزيلات الجديدة فقط. التنزيلات الحالية تبقى في مكانها. لا تفصل USB أثناء التنزيل أو التشغيل.")
            Text("عند فصل الذاكرة الخارجية يتحول الحفظ الجديد للداخلية تلقائياً، وعند إعادتها تعود هي المفضّلة. التنزيل الجاري لا ينتقل بين المسارين.")
            Text("المجلد مخصص للتطبيق: Downloads/Nuvio. حذف التطبيق قد يحذف هذه الملفات.")
            choices.forEach { location ->
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.select(location.id) }
                            selected = location.id
                            error = null
                            show = false
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.localizedMessage
                        } finally { busy = false }
                    }
                }) {
                    Column {
                        Text((if (location.id == selected) "✓ " else "") +
                            if (location.id == "internal") "ذاكرة الجهاز" else "ذاكرة خارجية / USB")
                        Text(location.folder.absolutePath)
                    }
                }
            }
            error?.let { Text(it) }
            Button(enabled = !busy, onClick = { refresh++ }) { Text("تحديث وحدات التخزين") }
            Text("تظهر فقط الوحدات التي يمنح Android التطبيق حق الكتابة عليها. بعض أجهزة التلفزيون لا تتيح USB للتنزيل.")
        }
    }
}
