package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class AudioTypeDelayPolicyTest {
    @Test fun `JOC metadata identifies Atmos without a title hint`() {
        assertTrue(isAtmosAudioFormat("audio/eac3-joc", null, null))
        assertTrue(isAtmosAudioFormat("audio/eac3", "ec+3", "English"))
        assertTrue(isAtmosAudioFormat(null, "EAC3_JOC", null))
    }
    @Test fun `plain codec families and channel counts never imply Atmos`() {
        for (codec in listOf("truehd", "eac3", "ac3", "dts", "aac", "pcm")) {
            assertFalse(codec, isAtmosAudioFormat("audio/$codec", codec, "7.1 English"))
        }
        assertFalse(isAtmosAudioFormat(null, null, null))
    }
    @Test fun `explicit track labels can identify TrueHD Atmos but false hints cannot`() {
        assertTrue(isAtmosAudioFormat("audio/true-hd", "truehd", "English Dolby Atmos 7.1"))
        assertFalse(isAtmosAudioFormat(null, "truehd", "Atmosphere"))
        assertFalse(isAtmosAudioFormat(null, "truehd", "Non-Atmos 7.1"))
        assertFalse(isAtmosAudioFormat(null, "truehd", "Atmos: false"))
    }
    @Test fun `switching types chooses one offset and never adds presets`() {
        assertEquals(-200, selectedAudioTypeDelayMs(true, 75, -200))
        assertEquals(75, selectedAudioTypeDelayMs(false, 75, -200))
        assertEquals(0, selectedAudioTypeDelayMs(true, 75, 0))
        assertEquals(-500, selectedAudioTypeDelayMs(true, 0, -9000))
        assertEquals(3000, selectedAudioTypeDelayMs(false, 9000, -200))
    }
}
