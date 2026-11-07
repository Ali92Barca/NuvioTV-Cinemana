package com.nuvio.tv.ui.screens.addon

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.ArabCinemetaPairingServer
import android.os.Handler
import android.os.Looper

private const val ARABCINEMETA_SITE = "https://arabcinemeta.osamayousry.com"

// Promotional poster for the 2025 Egyptian film Restart, starring Tamer Hosny.
// Loaded on demand (not packaged into the APK). Replace with a licensed local asset if distributing.
private const val RESTART_POSTER = "https://static.hiamag.com/inline-images/%D8%AA%D8%A7%D9%85%D8%B1%20%D8%AD%D8%B3%D9%86%D9%8A%20%D9%86%D8%AC%D9%85%20%D9%85%D8%AA%D9%83%D8%A7%D9%85%D9%84%20%D8%A8%D9%8A%D9%86%20%D8%A7%D9%84%D8%B3%D9%8A%D9%86%D9%85%D8%A7%20%D9%88%D8%A7%D9%84%D8%BA%D9%86%D8%A7%D8%A1%20%D9%88%D8%A7%D9%84%D8%A3%D8%B2%D9%8A%D8%A7%D8%A1.jpg"

@Composable
fun ArabCinemetaPromoDialog(onDismiss: (Boolean) -> Unit, onApproveManifest: (String) -> Unit) {
    var neverShowAgain by remember { mutableStateOf(false) }
    var continueFocused by remember { mutableStateOf(false) }
    var checkFocused by remember { mutableStateOf(false) }
    var rejectFocused by remember { mutableStateOf(false) }
    var approveFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingManifest by remember { mutableStateOf<String?>(null) }
    val pairingServer = remember {
        runCatching {
            ArabCinemetaPairingServer { url ->
                Handler(Looper.getMainLooper()).post { pendingManifest = url }
            }.also { it.start(3000, false) }
        }.getOrNull()
    }
    DisposableEffect(pairingServer) { onDispose { pairingServer?.stop() } }
    val pairingIp = remember { ArabCinemetaPairingServer.localIp() }
    val pairingUrl = remember(pairingServer, pairingIp) {
        if (pairingIp != null && pairingServer != null) pairingServer.qrUrl(pairingIp) else null
    }
    val qr = remember(pairingUrl) {
        pairingUrl?.let { runCatching { QrCodeGenerator.generate(it, 360, margin = 2) }.getOrNull() }
    }
    Dialog(onDismissRequest = { onDismiss(neverShowAgain) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            modifier = Modifier
                .width(900.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF121B2A))
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = RESTART_POSTER,
                contentDescription = "بوستر فيلم ريستارت لتامر حسني",
                modifier = Modifier.width(235.dp).height(360.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("خلّي السينما العربية أقرب إليك", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("اكتشف معلومات وبوسترات الأفلام والمسلسلات العربية مع ArabCinemeta", color = Color(0xFFCED7E5), fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(15.dp))
                Box(Modifier.clip(RoundedCornerShape(15.dp)).background(Color.White).padding(12.dp)) {
                    if (qr != null) {
                        Image(qr.asImageBitmap(), contentDescription = "QR لصفحة الربط المحلية على الهاتف", modifier = Modifier.size(150.dp))
                    } else {
                        Text("تعذر تشغيل خادم الربط المحلي. تأكد من اتصال CBOX بالشبكة.", color = Color.Red)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(pairingUrl?.substringBefore('#') ?: "الخادم المحلي غير متاح", color = Color(0xFFADD8FF), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("امسح الرمز بهاتفك لفتح صفحة الربط المحلية، وافتح موقع ArabCinemeta منها ثم أرسل رابط Manifest للتلفزيون.", color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                pendingManifest?.let { pending ->
                    Text("وصل رابط إضافة من الهاتف. هل تريد تثبيته؟", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(pending.take(100), color = Color(0xFFADD8FF), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onApproveManifest(pending); pendingManifest = null; onDismiss(neverShowAgain) }, modifier = Modifier.onFocusChanged { approveFocused = it.isFocused }
                            .border(if (approveFocused) 3.dp else 0.dp, if (approveFocused) Color.White else Color.Transparent, RoundedCornerShape(24.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = if (approveFocused) Color(0xFFFFC857) else Color(0xFF2768B8), contentColor = if (approveFocused) Color.Black else Color.White)) { Text("تأكيد التثبيت") }
                        Button(onClick = { pendingManifest = null }, modifier = Modifier.onFocusChanged { rejectFocused = it.isFocused }
                            .border(if (rejectFocused) 3.dp else 0.dp, if (rejectFocused) Color.White else Color.Transparent, RoundedCornerShape(24.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = if (rejectFocused) Color(0xFFFFC857) else Color(0xFF2768B8), contentColor = if (rejectFocused) Color.Black else Color.White)) { Text("رفض") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverShowAgain, onCheckedChange = { neverShowAgain = it },
                        modifier = Modifier.onFocusChanged { checkFocused = it.isFocused }
                            .border(if (checkFocused) 3.dp else 0.dp, if (checkFocused) Color(0xFFFFC857) else Color.Transparent, RoundedCornerShape(6.dp)),
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFC857), uncheckedColor = Color.White, checkmarkColor = Color.Black))
                    Text("لا تعرض مرة أخرى لهذا البروفايل", color = Color.White, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                    Button(
                        onClick = { onDismiss(neverShowAgain) },
                        modifier = Modifier.onFocusChanged { continueFocused = it.isFocused }
                            .border(if (continueFocused) 3.dp else 0.dp, if (continueFocused) Color.White else Color.Transparent, RoundedCornerShape(24.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = if (continueFocused) Color(0xFFFFC857) else Color(0xFF2768B8), contentColor = if (continueFocused) Color.Black else Color.White)
                    ) { Text("متابعة إلى نوفيو", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
