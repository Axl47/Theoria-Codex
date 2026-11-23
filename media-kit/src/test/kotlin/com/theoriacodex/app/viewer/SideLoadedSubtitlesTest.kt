package com.theoriacodex.app.viewer

import android.app.Application
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SideLoadedSubtitlesTest {
    @Test fun `an address names its subtitle format by extension, ignoring the query`() {
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType("https://subs.test/en.vtt?token=a.srt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType("https://subs.test/en.SRT"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType("https://subs.test/en.ass"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType("https://subs.test/en.ssa"))
        assertEquals(MimeTypes.APPLICATION_TTML, subtitleMimeType("https://subs.test/en.ttml"))
        assertEquals(UNDETERMINED_SUBTITLE_MIME, subtitleMimeType("https://subs.test/subtitle?id=4"))
    }

    @Test fun `a file without one is read by its first bytes`() {
        fun sniff(text: String) = sniffedSubtitleMimeType(text.toByteArray())

        assertEquals(MimeTypes.TEXT_VTT, sniff("\uFEFFWEBVTT\n\n00:00.000 --> 00:01.000\nHi"))
        assertEquals(MimeTypes.TEXT_SSA, sniff("\n[Script Info]\nScriptType: v4.00+"))
        assertEquals(MimeTypes.APPLICATION_TTML, sniff("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"/>"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sniff("1\r\n00:00:01,000 --> 00:00:02,000\r\nHi"))
        assertNull(sniff("<html>nope</html>".drop(1)))
    }
}
