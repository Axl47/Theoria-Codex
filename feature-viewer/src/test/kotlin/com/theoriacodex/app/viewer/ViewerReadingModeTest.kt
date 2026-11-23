package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerReadingModeTest {
    private val image = ImageRef(url = "https://example.test/1.jpg", localPath = null, mime = "image/jpeg")
    private val video = ImageRef(url = "https://example.test/clip.mp4", localPath = null, mime = "video/mp4")

    @Test fun `a gallery's saved mode wins over the global direction`() {
        val gallery = page(listOf(image, image))
        val config = ViewerScreenConfig(invertMultiImageScrollDirection = true, galleryReadingModes = mapOf(gallery.post.id to GalleryReadingMode.LEFT_TO_RIGHT))
        assertEquals(GalleryReadingMode.LEFT_TO_RIGHT, config.readingModeOf(gallery))
        assertEquals(GalleryReadingMode.RIGHT_TO_LEFT, ViewerScreenConfig(invertMultiImageScrollDirection = true).readingModeOf(gallery))
    }

    @Test fun `a single page always runs left to right`() {
        val single = page(listOf(image))
        val config = ViewerScreenConfig(invertMultiImageScrollDirection = true, galleryReadingModes = mapOf(single.post.id to GalleryReadingMode.VERTICAL))
        assertEquals(GalleryReadingMode.LEFT_TO_RIGHT, config.readingModeOf(single))
    }

    @Test fun `a series' one-page chapter keeps the series' mode`() {
        val single = page(listOf(image))
        for (mode in listOf(GalleryReadingMode.RIGHT_TO_LEFT, GalleryReadingMode.VERTICAL)) {
            val config = ViewerScreenConfig(series = ViewerSeries.Reading(mode), galleryReadingModes = mapOf(single.post.id to mode))
            assertEquals(mode, config.readingModeOf(single))
        }
    }

    @Test fun `vertical gives way while a gallery holds pages that are not still images`() {
        val mixed = page(listOf(image, video))
        val config = ViewerScreenConfig(galleryReadingModes = mapOf(mixed.post.id to GalleryReadingMode.VERTICAL))
        assertEquals(GalleryReadingMode.LEFT_TO_RIGHT, config.readingModeOf(mixed))
        val stills = page(listOf(image, image))
        assertEquals(GalleryReadingMode.VERTICAL, ViewerScreenConfig(galleryReadingModes = mapOf(stills.post.id to GalleryReadingMode.VERTICAL)).readingModeOf(stills))
    }

    @Test fun `a series chapter left on its last page is entered at its first, and any other post where it was left`() {
        val pages = listOf(image, image, image)
        val series = ViewerScreenConfig(series = ViewerSeries.Reading(GalleryReadingMode.LEFT_TO_RIGHT))
        assertEquals(0, series.entryMediaIndex(page(pages).copy(selectedMediaIndex = 2)))
        assertEquals(2, series.entryMediaIndex(page(pages).copy(selectedMediaIndex = 2), back = true))
        assertEquals(1, series.entryMediaIndex(page(pages).copy(selectedMediaIndex = 1)))
        // A chapter whose pages have not arrived is still on its way to the page it resumes at.
        assertEquals(2, series.entryMediaIndex(page(listOf(image)).copy(pendingMediaIndex = 2)))
        assertEquals(2, ViewerScreenConfig().entryMediaIndex(page(pages).copy(selectedMediaIndex = 2)))
    }

    private fun page(media: List<ImageRef>) = requireNotNull(createViewerUiState(ViewerSessionIdentity("modes"), listOf(Post(
        id = PostId(SourceKey.NHENTAI, "g"), preview = media.first(), full = media.first(), media = media, pageUrl = null,
        width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    ))).currentPage)
}
