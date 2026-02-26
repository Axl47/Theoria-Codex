package com.theoriacodex.sources.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMimeTest {
    @Test
    fun `mime from file extension supports images and videos`() {
        assertEquals("image/jpeg", mimeFromFileExt("jpg"))
        assertEquals("video/mp4", mimeFromFileExt("mp4"))
        assertEquals("video/webm", mimeFromFileExt(".webm"))
        assertNull(mimeFromFileExt("unknown"))
    }

    @Test
    fun `infer mime from url supports query and fragment`() {
        assertEquals("video/mp4", inferMimeFromUrl("https://example.test/file.MP4?x=1#t=2"))
        assertEquals("image/webp", inferMimeFromUrl("https://example.test/file.webp?token=abc"))
        assertNull(inferMimeFromUrl("https://example.test/file"))
    }
}
