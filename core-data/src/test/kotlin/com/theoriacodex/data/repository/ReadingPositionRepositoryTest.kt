package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    fun `observers receive backwards progress and profile replacement`() = runTest {
        val repository = FileBackedReadingPositionRepository(temporary.newFolder())
        assertEquals(emptyList<ReadingPosition>(), repository.observe().first())
        repository.record(position("book", 9, 100))
        repository.record(position("book", 3, 200))
        assertEquals(3, repository.observe().first().single().mediaNumber)
        repository.replace(emptyList())
        assertTrue(repository.observe().first().isEmpty())
    }

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
    fun `bounded merge is idempotent preserves newer local positions and unrelated entries (ADR 0025)`() = runTest {
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
    fun `replace supports exact rollback`() = runTest {
        val repository = FileBackedReadingPositionRepository(temporary.newFolder())
        val initial = listOf(position("one", 3, 10))
        repository.replace(initial)
        repository.merge(listOf(position("one", 9, 100)))
        assertEquals(1, repository.snapshot().size)
        repository.replace(initial)
        assertEquals(initial, repository.snapshot())
    }

    @Test
    fun `malformed entries do not discard valid positions (ADR 0021, 0033)`() = runTest {
        val directory = temporary.newFolder()
        directory.resolve(READING_POSITIONS_FILE_NAME).writeText("""
            {"schemaVersion":1,"positions":[
              {"source":"PIXIV","postId":"valid","section":"WATCHED","mediaNumber":3,"lastViewedAt":5},
              {"source":"FUTURE","postId":"future","section":"WATCHED","mediaNumber":4,"lastViewedAt":6},
              {"source":"PIXIV","postId":"invalid","section":"WATCHED","mediaNumber":0,"lastViewedAt":7},
              {"source":"PIXIV","postId":"wrong-type","section":"WATCHED","mediaNumber":"three","lastViewedAt":8},
              {"source":"PIXIV","postId":"missing-section","mediaNumber":1,"lastViewedAt":9},
              "not a record", null
            ]}
        """.trimIndent())
        assertEquals(listOf(position("valid", 3, 5)), FileBackedReadingPositionRepository(directory).snapshot())
    }

    @Test
    fun `one swipe reported twice writes once and keeps the first timestamp (ADR 0021)`() = runTest {
        val directory = temporary.newFolder()
        val file = directory.resolve(READING_POSITIONS_FILE_NAME)
        val repository = FileBackedReadingPositionRepository(directory)
        repository.record(position("book", 4, 1_000))
        file.setLastModified(0L)

        repository.record(position("book", 4, 1_200))
        assertEquals(0L, file.lastModified())
        assertEquals(1_000L, repository.get(id("book"), RecentPostSection.WATCHED)?.lastViewedAt)

        repository.record(position("book", 4, 1_000 + READING_POSITION_COALESCE_MS))
        assertEquals(1_000L + READING_POSITION_COALESCE_MS, repository.snapshot().single().lastViewedAt)
    }

    @Test
    fun `a file the Gson store wrote loads the same positions`() = runTest {
        val directory = temporary.newFolder()
        directory.resolve(READING_POSITIONS_FILE_NAME).writeText(golden("gson-written.json"))

        assertEquals(
            listOf(
                ReadingPosition(PostId(SourceKey.parseOrThrow("custom_safebooru_org"), "tom & jerry's <cut>=1"),
                    RecentPostSection.CODEX, 12, 1_700_000_100_000L),
                ReadingPosition(PostId(SourceKey.PIXIV, "12345"), RecentPostSection.WATCHED, 3, 1_700_000_000_000L),
                ReadingPosition(PostId(SourceKey.GELBOORU, "é☃"), RecentPostSection.WATCHED, 1, 0L),
            ),
            FileBackedReadingPositionRepository(directory).snapshot(),
        )
    }

    @Test
    fun `the reading positions golden pins every stored key`() {
        val golden = golden("gson-written.json")
        val entries = (DurableJson.parseToJsonElement(golden) as JsonObject).getValue("positions") as JsonArray
        assertStoredFormat(golden to ReadingPositionsFile(1, entries))
        assertStoredFormat(
            entries[0].toString() to ReadingPositionRecord("custom_safebooru_org", "tom & jerry's <cut>=1", "CODEX", 12, 1_700_000_100_000L),
            entries[1].toString() to ReadingPositionRecord("PIXIV", "12345", "WATCHED", 3, 1_700_000_000_000L),
            entries[2].toString() to ReadingPositionRecord("GELBOORU", "é☃", "WATCHED", 1, 0L),
        )
    }

    @Test
    fun `a missing schema reads as version 1 and a newer schema fails without touching the file (ADR 0021)`() = runTest {
        val directory = temporary.newFolder()
        val file = directory.resolve(READING_POSITIONS_FILE_NAME)
        file.writeText("""{"positions":[{"source":"PIXIV","postId":"a","section":"WATCHED","mediaNumber":2,"lastViewedAt":1}]}""")
        assertEquals(listOf(position("a", 2, 1)), FileBackedReadingPositionRepository(directory).snapshot())

        listOf("""{"schemaVersion":2,"positions":[]}""", """{"schemaVersion":2,"positions":{}}""").forEach { newer ->
            file.writeText(newer)
            assertTrue(runCatching { FileBackedReadingPositionRepository(directory).snapshot() }.isFailure)
            assertEquals(newer, file.readText())
        }
    }

    @Test
    fun `an unreadable file is set aside and positions start empty`() = runTest {
        val directory = temporary.newFolder()
        directory.resolve(READING_POSITIONS_FILE_NAME).writeText("""{"schemaVersion":1,"positions":[""")
        assertEquals(emptyList<ReadingPosition>(), FileBackedReadingPositionRepository(directory).snapshot())
        assertEquals(1, directory.listFiles().orEmpty().count { it.name.startsWith("$READING_POSITIONS_FILE_NAME.corrupt-") })
    }

    private fun id(value: String) = PostId(SourceKey.PIXIV, value)
    private fun position(value: String, page: Int, time: Long, section: RecentPostSection = RecentPostSection.WATCHED) =
        ReadingPosition(id(value), section, page, time)
    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/reading-positions/$name")) { "missing golden $name" }.readText()
}
