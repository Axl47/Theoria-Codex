// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to the main cases of its ChapterRecognitionTest.
package com.theoriacodex.extensionhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterRecognitionTest {
    @Test
    fun `the chapter tag wins over a volume number`() {
        assertEquals(4f, ChapterRecognition.parseChapterNumber("Mokushiroku Alice", "Mokushiroku Alice Vol.1 Ch.4: Misrepresentation"))
        assertEquals(3f, ChapterRecognition.parseChapterNumber("Series", "Vol.1 Ch.3 - Title"))
        assertEquals(4f, ChapterRecognition.parseChapterNumber("Series", "Vol. 1 Chapter 4"))
    }

    @Test
    fun `decimals and suffixes stay with their chapter`() {
        assertEquals(10.5f, ChapterRecognition.parseChapterNumber("Series", "Ch. 10.5"))
        assertEquals(4.99f, ChapterRecognition.parseChapterNumber("Series", "Chapter 4 extra"))
        assertEquals(12.97f, ChapterRecognition.parseChapterNumber("Series", "Chapter 12 special"))
        assertEquals(7.1f, ChapterRecognition.parseChapterNumber("Series", "Chapter 7a"))
    }

    @Test
    fun `digits in the series title are not the chapter's`() {
        assertEquals(12f, ChapterRecognition.parseChapterNumber("Kaiju No. 8", "Kaiju No. 8 Chapter 12"))
        assertEquals(567f, ChapterRecognition.parseChapterNumber("Bleach", "Bleach 567: Down With Snowwhite"))
    }

    @Test
    fun `a series titled with a number leaves chapter numbers whole`() {
        assertEquals(186f, ChapterRecognition.parseChapterNumber("86", "Chapter 186"))
        assertEquals(86f, ChapterRecognition.parseChapterNumber("86", "Chapter 86"))
        assertEquals(12f, ChapterRecognition.parseChapterNumber("86", "86 Chapter 12"))
    }

    @Test
    fun `a name without a number has none`() {
        assertNull(ChapterRecognition.parseChapterNumber("Series", "Oneshot"))
    }
}
