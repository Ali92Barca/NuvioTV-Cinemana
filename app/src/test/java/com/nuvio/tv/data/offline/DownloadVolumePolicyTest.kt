package com.nuvio.tv.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadVolumePolicyTest {
    @Test fun `unplug and reconnect preserve external preference`() {
        val preferred = "/storage/ABCD/Android/data/com.nuvio.tv/files/Download"
        assertEquals(preferred, effectiveDownloadVolume(preferred, listOf("internal", preferred)))
        assertEquals("internal", effectiveDownloadVolume(preferred, listOf("internal")))
        assertEquals(preferred, effectiveDownloadVolume(preferred, listOf("internal", preferred)))
    }
    @Test fun `different USB does not replace the preferred volume`() {
        assertEquals("internal", effectiveDownloadVolume("usb-a", listOf("internal", "usb-b")))
    }
    @Test fun `explicit internal selection remains internal after USB reconnects`() {
        assertEquals("internal", effectiveDownloadVolume("internal", listOf("internal", "usb-a")))
    }
    @Test fun `no storage yields no destination`() {
        assertNull(effectiveDownloadVolume("usb-a", emptyList()))
        assertNull(effectiveDownloadVolume("usb-a", listOf("usb-b")))
    }
}
