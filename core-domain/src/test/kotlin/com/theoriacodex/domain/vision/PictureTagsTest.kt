package com.theoriacodex.domain.vision

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PictureTagsTest {
    @Test
    fun `without a tagger nothing is ready, known or seen (ADR 0005)`() = runBlocking {
        val post = Post(
            id = PostId(SourceKey.DANBOORU, "1"),
            preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )

        assertFalse(PictureTags.None.ready.value)
        assertEquals(PredictedTagIndex.Empty, PictureTags.None.library.value)
        assertNull(PictureTags.None.known(post.id))
        assertEquals(PictureLook.Blind, PictureTags.None.look(post))
    }
}
