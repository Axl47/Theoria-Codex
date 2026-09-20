package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingPositionRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `last page can move backwards and survives reopening independently per section`() = runTest {
        val directory = temporary.newFolder()
        val repository = FileBackedReadingPositionRepository(directory)
        repository.record(position("book", 9, 100))
        repository.record(position("book", 3, 200))
        repository.record(position("book", 7, 150, RecentPostSection.CODEX))
        repository.record(position("book", 8, 50))

        val reopened = FileBackedReadingPositionRepository(directory)
        assertEquals(3, reopened.get(id("book"), RecentPostSection.WATCHED)?.mediaNumber)
        assertEquals(7, reopened.get(id("book"), RecentPostSection.CODEX)?.mediaNumber)
        assertTrue(directory.resolve(READING_POSITIONS_FILE_NAME).readText().contains("\"mediaNumber\""))
    }

    @Test
    fun `bounded merge is idempotent preserves newer local positions and unrelated entries`() = runTest {
        val repository = FileBackedReadingPositionRepository(temporary.newFolder(), entryLimit = 3)
        repository.record(position("local", 4, 400))
        repository.record(position("old", 1, 10))
        val backup = listOf(position("local", 2, 200), position("imported", 8, 300), position("other", 2, 250))
        repository.merge(backup)
        repository.merge(backup)

        assertEquals(listOf("local", "imported", "other"), repository.snapshot().map { it.postId.sourcePostId })
        assertEquals(4, repository.get(id("local"), RecentPostSection.WATCHED)?.mediaNumber)
        assertNull(repository.get(id("old"), RecentPostSection.WATCHED))
    }

    @Test
    fun `replace supports exact rollback and ignores obsolete FYP post membership`() = runTest {
        val repository = FileBackedReadingPositionRepository(temporary.newFolder())
        val initial = listOf(position("one", 3, 10))
        repository.replace(initial)
        repository.merge(listOf(position("one", 9, 100), position("retired", 1, 90, RecentPostSection.FYP)))
        assertEquals(1, repository.snapshot().size)
        repository.replace(initial)
        assertEquals(initial, repository.snapshot())
    }

    @Test
    fun `malformed entries do not discard valid positions`() = runTest {
        val directory = temporary.newFolder()
        directory.resolve(READING_POSITIONS_FILE_NAME).writeText("""
            {"schemaVersion":1,"positions":[
              {"source":"PIXIV","postId":"valid","section":"WATCHED","mediaNumber":3,"lastViewedAt":5},
              {"source":"FUTURE","postId":"future","section":"WATCHED","mediaNumber":4,"lastViewedAt":6},
              {"source":"PIXIV","postId":"invalid","section":"WATCHED","mediaNumber":0,"lastViewedAt":7}
            ]}
        """.trimIndent())
        assertEquals(listOf(position("valid", 3, 5)), FileBackedReadingPositionRepository(directory).snapshot())
    }

    private fun id(value: String) = PostId(SourceKey.PIXIV, value)
    private fun position(value: String, page: Int, time: Long, section: RecentPostSection = RecentPostSection.WATCHED) =
        ReadingPosition(id(value), section, page, time)
}
