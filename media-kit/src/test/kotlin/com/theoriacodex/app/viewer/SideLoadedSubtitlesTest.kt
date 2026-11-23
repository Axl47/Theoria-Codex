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
        assertNull(subtitleMimeType("https://subs.test/subtitle?id=4"))
    }

    @Test fun `a file's first bytes name its format, in UTF-8 or UTF-16`() {
        fun sniff(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8) = sniffedSubtitleMimeType(text.toByteArray(charset))

        assertEquals(MimeTypes.TEXT_VTT, sniff("\uFEFFWEBVTT\n\n00:00.000 --> 00:01.000\nHi"))
        assertEquals(MimeTypes.TEXT_SSA, sniff("\n[Script Info]\nScriptType: v4.00+"))
        assertEquals(MimeTypes.APPLICATION_TTML, sniff("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"/>"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sniff("1\r\n00:00:01,000 --> 00:00:02,000\r\nHi"))
        assertEquals(MimeTypes.APPLICATION_TTML, sniff("<tt xmlns=\"http://www.w3.org/ns/ttml\"/>"))
        // Java's UTF-16 encoder writes a big-endian byte order mark.
        assertEquals(MimeTypes.TEXT_VTT, sniff("WEBVTT\n", Charsets.UTF_16))
        assertEquals(MimeTypes.TEXT_SSA, sniff("\uFEFF[Script Info]", Charsets.UTF_16LE))
        assertNull(sniff("<html><body>Not found</body></html>"))
    }
}
