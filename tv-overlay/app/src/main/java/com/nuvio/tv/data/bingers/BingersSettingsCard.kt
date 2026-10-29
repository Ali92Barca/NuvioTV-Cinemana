@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.data.bingers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BingersSettingsViewModel @Inject constructor(val service: BingersService) : ViewModel()

/** Ordinary TV buttons participate in D-pad focus; text fields open the device keyboard. */
@Composable
fun BingersSettingsCard(viewModel: BingersSettingsViewModel = hiltViewModel()) {
    val service = viewModel.service
    val state by service.state.collectAsState()
    var link by remember { mutableStateOf("") }
    LaunchedEffect(service) { service.refresh() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bingers • تتبع المشاهدة عند 80٪")
        Text(state.message)
        Text("الحساب المجاني: لم يُتحقق من صلاحية المزامنة بعد. يتم إظهار أي طلب اشتراك من الخدمة.")
        if (!state.linked) {
            Text("اطلب رسالة دخول من موقع Bingers، والصق رابط الدخول الجديد هنا داخل التطبيق.")
            OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), label = { androidx.compose.material3.Text("رابط دخول Bingers") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { service.link(link); link = "" }, enabled = !state.busy && link.isNotBlank()) { Text("ربط الحساب") }
        } else {
            Button(onClick = { service.setEnabled(!state.enabled) }, enabled = !state.busy) {
                Text(if (state.enabled) "إيقاف المزامنة" else "تفعيل المزامنة")
            }
            Button(onClick = service::retry, enabled = !state.busy) { Text("إعادة محاولة المزامنة (${state.pending} معلقة، ${state.unmatched} غير مطابقة)") }
            Button(onClick = service::disconnect, enabled = !state.busy) { Text("فصل الحساب") }
        }
    }
}
