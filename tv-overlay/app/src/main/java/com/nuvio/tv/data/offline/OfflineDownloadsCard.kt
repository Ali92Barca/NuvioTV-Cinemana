@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Stream
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(val downloads: OfflineDownloads) : ViewModel() {
    val message = MutableStateFlow("")
    fun enqueue(stream: Stream) { viewModelScope.launch(Dispatchers.IO) {
        message.value = try { downloads.enqueue(stream); "أُضيف الفيديو والترجمات إلى التنزيلات" }
            catch (error: Exception) { error.message ?: "تعذر بدء التنزيل" }
    } }
    fun remove(key: String) { viewModelScope.launch(Dispatchers.IO) { downloads.remove(key) } }
}

@Composable
fun OfflineDownloadButton(stream: Stream, viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    if (!stream.addonName.contains("Cinemana", true) || stream.url.isNullOrBlank()) return
    val message by viewModel.message.collectAsState()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.enqueue(stream) }) { Text("تنزيل الفيديو والترجمات") }
        if (message.isNotBlank()) Text(message)
    }
}

@Composable
fun OfflineDownloadsCard(viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(emptyList<Triple<OfflineItem, Boolean, String>>()) }
    LaunchedEffect(viewModel) {
        while (true) {
            items = withContext(Dispatchers.IO) {
                viewModel.downloads.list().map { item ->
                    val state = viewModel.downloads.transfer(item.video)
                    val status = when {
                        viewModel.downloads.ready(item) -> "جاهز بدون إنترنت"
                        state?.status == DownloadManager.STATUS_FAILED -> "فشل التنزيل؛ احذفه وأعد المحاولة"
                        state?.status == DownloadManager.STATUS_SUCCESSFUL -> "الفيديو مكتمل؛ الترجمة غير مكتملة"
                        state == null -> "الملف غير موجود"
                        else -> "تنزيل: ${state.downloaded / (1024 * 1024)} ميغابايت"
                    }
                    Triple(item, viewModel.downloads.ready(item), status)
                }
            }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("التنزيلات • تشغيل بدون إنترنت")
        Text("تدعم مصادر Cinemana المباشرة؛ HLS وDASH غير مدعومين حالياً.")
        if (items.isEmpty()) Text("اختر تنزيل الفيديو والترجمات أسفل رابط المشاهدة.")
        items.forEach { (item, ready, status) ->
            Text(item.title)
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { context.startActivity(Intent(context, OfflinePlayerActivity::class.java).putExtra("item", item.key)) }, enabled = ready) { Text("تشغيل") }
                Button(onClick = { viewModel.remove(item.key) }) { Text("حذف / إلغاء") }
            }
        }
    }
}
