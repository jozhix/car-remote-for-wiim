package dev.wiimauto.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFormatTest {

    private fun format(rate: String, depth: String, kbps: String) =
        TrackMeta("", "", "", "", "", rate, depth, kbps).formatLine

    @Test
    fun showsFullFormat() = assertEquals("320 kbps | 24 bit / 44.1 kHz", format("44100", "24", "320"))

    @Test
    fun hidesInternal32Bit() = assertEquals("128 kbps | 44.1 kHz", format("44100", "32", "128"))

    @Test
    fun wholeKilohertzHasNoDecimal() = assertEquals("16 bit / 48 kHz", format("48000", "16", "0"))

    @Test
    fun unknownValuesAreBlank() = assertEquals("", format("", "", ""))
}
