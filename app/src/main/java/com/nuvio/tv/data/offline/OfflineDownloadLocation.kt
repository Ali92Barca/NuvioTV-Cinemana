package com.nuvio.tv.data.offline

import android.content.Context
import android.os.Environment
import java.io.File

/** Only Android-granted app directories. No guessed USB paths or broad storage grants. */
class OfflineDownloadLocation(private val context: Context) {
    data class Location(val id: String, val folder: File, val removable: Boolean)
    private val prefs = context.getSharedPreferences("nuvio_download_location_v1", Context.MODE_PRIVATE)
    fun selectedId(): String = prefs.getString("volume", "internal") ?: "internal"

    fun locations(): List<Location> = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS)
        .mapIndexedNotNull { index, base ->
            base ?: return@mapIndexedNotNull null
            if (Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return@mapIndexedNotNull null
            val removable = Environment.isExternalStorageRemovable(base)
            if (index != 0 && !removable) return@mapIndexedNotNull null
            Location(if (index == 0) "internal" else base.canonicalPath, File(base, "Nuvio"), removable)
        }

    fun select(id: String) {
        val location = locations().firstOrNull { it.id == id }
            ?: error("مكان التنزيل غير متصل. أعد توصيل الذاكرة أو اختر الذاكرة الداخلية")
        validate(location.folder)
        check(prefs.edit().putString("volume", id).commit()) { "تعذر حفظ مكان التنزيل" }
    }

    fun destinationFolder(): File {
        val selected = selectedId()
        val available = locations()
        // Keep the preferred external ID. Absence affects this new item only;
        // reconnecting that volume automatically restores it for the next item.
        val effective = effectiveDownloadVolume(selected, available.map { it.id })
        val location = available.firstOrNull { it.id == effective }
            ?: error("ذاكرة الجهاز غير متاحة للتنزيل")
        return validate(location.folder)
    }

    private fun validate(folder: File): File {
        check(folder.isDirectory || folder.mkdirs()) { "تعذر إنشاء مجلد التنزيل" }
        check(folder.canWrite()) { "مكان التنزيل غير قابل للكتابة" }
        // Unique empty probe only; never truncate an existing user file.
        File.createTempFile(".nuvio-probe-", ".tmp", folder).let { probe ->
            check(probe.delete()) { "تعذر التحقق من مكان التنزيل" }
        }
        return folder
    }
}
