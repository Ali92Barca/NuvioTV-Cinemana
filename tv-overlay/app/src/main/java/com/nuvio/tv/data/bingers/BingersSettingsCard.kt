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
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bingers • تتبع المشاهدة عند 80٪")
        Text("واجهة ربط التطبيقات الرسمية تتطلب اشتراك Legend أو Icon. الحساب المجاني غير مدعوم رسمياً لهذا الربط.")
        Text("الإرسال التلقائي معطّل. لم يُختبر حساب مدفوع، ولا يجري استخدام جلسة التطبيق كبديل للاشتراك.")
    }
}
