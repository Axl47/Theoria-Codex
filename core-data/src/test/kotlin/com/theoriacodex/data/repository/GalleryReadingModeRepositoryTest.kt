package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryReadingModeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a choice replaces the gallery's previous one and survives reopening`() = runTest {
        val directory = temporary.newFolder()
        val repository = FileBackedGalleryReadingModeRepository(directory)
        repository.set(id("book"), GalleryReadingMode.RIGHT_TO_LEFT)
        repository.set(id("strip"), GalleryReadingMode.VERTICAL)
        repository.set(id("book"), GalleryReadingMode.LEFT_TO_RIGHT)

        assertEquals(
            mapOf(id("book") to GalleryReadingMode.LEFT_TO_RIGHT, id("strip") to GalleryReadingMode.VERTICAL),
            FileBackedGalleryReadingModeRepository(directory).observe().first(),
        )
    }

    @Test
    fun `only the most recent choices are kept`() = runTest {
        val repository = FileBackedGalleryReadingModeRepository(temporary.newFolder(), entryLimit = 2)
        repository.set(id("old"), GalleryReadingMode.VERTICAL)
        repository.set(id("middle"), GalleryReadingMode.VERTICAL)
        repository.set(id("new"), GalleryReadingMode.VERTICAL)

        assertEquals(setOf(id("middle"), id("new")), repository.observe().first().keys)
    }

    @Test
    fun `unreadable and extension-source entries are skipped and a newer schema fails without touching the file`() = runTest {
        val directory = temporary.newFolder()
        val file = directory.resolve(GALLERY_READING_MODES_FILE_NAME)
        file.writeText(
            """{"schemaVersion":1,"choices":[{"source":"PIXIV","postId":"valid","mode":"VERTICAL"},""" +
                """{"source":"PIXIV","postId":"future","mode":"SPREAD"},{"source":"FUTURE","postId":"x","mode":"VERTICAL"},""" +
                """{"source":"custom_mihon_7","postId":"L3M","mode":"VERTICAL"},"junk",null]}""",
        )
        assertEquals(mapOf(id("valid") to GalleryReadingMode.VERTICAL), FileBackedGalleryReadingModeRepository(directory).observe().first())

        val newer = """{"schemaVersion":2,"choices":[]}"""
        file.writeText(newer)
        assertTrue(runCatching { FileBackedGalleryReadingModeRepository(directory).observe().first() }.isFailure)
        assertEquals(newer, file.readText())
    }

    @Test
    fun `the stored format pins every key`() {
        val record = """{"source":"PIXIV","postId":"12345","mode":"RIGHT_TO_LEFT"}"""
        assertStoredFormat(record to GalleryReadingModeRecord("PIXIV", "12345", "RIGHT_TO_LEFT"))
        assertStoredFormat("""{"schemaVersion":1,"choices":[]}""" to GalleryReadingModesFile(1, JsonArray(emptyList())))
    }

    private fun id(value: String) = PostId(SourceKey.PIXIV, value)
}
