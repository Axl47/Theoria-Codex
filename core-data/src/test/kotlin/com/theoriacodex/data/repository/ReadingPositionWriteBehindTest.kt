package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.writeBehind
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingPositionWriteBehindTest {
    @get:Rule val temporary = TemporaryFolder()

    private val writeClock = TestCoroutineScheduler()
    private val writeScope = CoroutineScope(StandardTestDispatcher(writeClock))

    @Test
    fun `rapid page turns write once with the newest page (ADR 0215)`() = runTest {
        val writes = mutableListOf<List<ReadingPosition>>()
        val repository = CellReadingPositionRepository(
            FileStateCell(emptyList<ReadingPosition>()) { writes += it }.writeBehind(writeScope, READING_POSITION_WRITE_DELAY),
        )
        (1..20).forEach { page -> repository.record(position(page, page * 100L)) }
        writeClock.runCurrent()
        assertEquals(0, writes.size)
        assertEquals(20, repository.get(BOOK, RecentPostSection.WATCHED)?.mediaNumber)

        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds + 1)

        assertEquals(listOf(listOf(position(20, 2_000L))), writes)
    }

    @Test
    fun `a page turned during a write is written after it (ADR 0215)`() = runTest {
        val writes = mutableListOf<Int>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val repository = CellReadingPositionRepository(
            FileStateCell(emptyList<ReadingPosition>()) { positions ->
                if (writes.isEmpty()) {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
                writes += positions.single().mediaNumber
            }.writeBehind(writeScope, READING_POSITION_WRITE_DELAY),
        )
        repository.record(position(1, 100L))
        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds + 1)
        firstWriteStarted.await()

        repository.record(position(2, 200L))
        releaseFirstWrite.complete(Unit)
        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds + 1)

        assertEquals(listOf(1, 2), writes)
    }

    @Test
    fun `stopping the app flushes so a swiped-away chapter resumes on its last page (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val repository = FileBackedReadingPositionRepository(directory, writeScope = writeScope)
        (3..7).forEach { page -> repository.record(position(page, page * 100L)) }

        assertEquals(listOf(position(7, 700L)), repository.snapshot())
        assertNull(FileBackedReadingPositionRepository(directory).get(BOOK, RecentPostSection.WATCHED))

        repository.flush()

        assertEquals(7, FileBackedReadingPositionRepository(directory).get(BOOK, RecentPostSection.WATCHED)?.mediaNumber)
    }

    @Test
    fun `cancelling during the delay keeps the last written file readable (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val repository = FileBackedReadingPositionRepository(directory, writeScope = writeScope)
        repository.record(position(4, 400L))
        repository.flush()
        repository.record(position(9, 900L))

        writeScope.cancel()
        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds + 1)

        assertEquals(listOf(position(4, 400L)), FileBackedReadingPositionRepository(directory).snapshot())
    }

    @Test
    fun `restored positions are on disk before restore returns (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val stoppedScope = CoroutineScope(Job().apply { cancel() })
        val repository = FileBackedReadingPositionRepository(directory, writeScope = stoppedScope)

        repository.replace(listOf(position(2, 200L)))
        assertEquals(listOf(position(2, 200L)), FileBackedReadingPositionRepository(directory).snapshot())
        repository.merge(listOf(position(5, 500L)))

        assertEquals(listOf(position(5, 500L)), FileBackedReadingPositionRepository(directory).snapshot())
    }

    @Test
    fun `a page recorded just before the Viewer closes is in the stop flush (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val repository = FileBackedReadingPositionRepository(directory, writeScope = writeScope)
        repository.snapshot()
        val viewerScope = CoroutineScope(Job())
        viewerScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.record(position(8, 800L)) }
        viewerScope.cancel()

        repository.flush()

        assertEquals(listOf(position(8, 800L)), FileBackedReadingPositionRepository(directory).snapshot())
    }

    @Test
    fun `a failed write is retried without another page turn and a clean flush writes nothing (ADR 0215)`() = runTest {
        val writes = mutableListOf<Int>()
        var failNext = true
        val repository = CellReadingPositionRepository(
            FileStateCell(emptyList<ReadingPosition>()) { positions ->
                if (failNext) {
                    failNext = false
                    throw java.io.IOException("disk busy")
                }
                writes += positions.single().mediaNumber
            }.writeBehind(writeScope, READING_POSITION_WRITE_DELAY),
        )
        repository.record(position(3, 300L))

        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds * 2 + 1)
        repository.flush()
        writeClock.advanceTimeBy(READING_POSITION_WRITE_DELAY.inWholeMilliseconds * 4)

        assertEquals(listOf(3), writes)
    }

    private fun position(page: Int, time: Long) = ReadingPosition(BOOK, RecentPostSection.WATCHED, page, time)

    private companion object {
        val BOOK = PostId(SourceKey.PIXIV, "book")
    }
}
