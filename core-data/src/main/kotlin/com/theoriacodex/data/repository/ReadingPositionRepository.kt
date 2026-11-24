package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.decodeEachOrSkip
import com.theoriacodex.data.storage.update
import com.theoriacodex.data.storage.writeBehind
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

data class ReadingPosition(
    val postId: PostId,
    val section: RecentPostSection,
    val mediaNumber: Int,
    val lastViewedAt: Long,
) {
    init {
        require(mediaNumber > 0)
        require(lastViewedAt >= 0L)
    }
}

interface ReadingPositionRepository {
    fun observe(): Flow<List<ReadingPosition>>
    suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition?
    suspend fun record(position: ReadingPosition)
    suspend fun snapshot(): List<ReadingPosition>
    suspend fun replace(positions: List<ReadingPosition>)
    suspend fun merge(positions: List<ReadingPosition>)
    suspend fun flush()
}

const val READING_POSITIONS_FILE_NAME = "reading_positions.json"
const val MAX_READING_POSITIONS = 2_000

internal const val READING_POSITION_COALESCE_MS = 5_000L
internal val READING_POSITION_WRITE_DELAY = 2.seconds

class FileBackedReadingPositionRepository private constructor(repository: ReadingPositionRepository) :
    ReadingPositionRepository by repository {
    constructor(
        baseDirectory: File,
        store: AtomicJsonFileStore = AtomicJsonFileStore(),
        entryLimit: Int = MAX_READING_POSITIONS,
        writeScope: CoroutineScope? = null,
        writeDelay: Duration = READING_POSITION_WRITE_DELAY,
    ) : this(fileBackedReadingPositions(baseDirectory.resolve(READING_POSITIONS_FILE_NAME), store, entryLimit, writeScope, writeDelay))
}

private fun fileBackedReadingPositions(
    file: File,
    store: AtomicJsonFileStore,
    entryLimit: Int,
    writeScope: CoroutineScope?,
    writeDelay: Duration,
): ReadingPositionRepository {
    require(entryLimit > 0)
    val cell = FileStateCell(
        load = { decodeReadingPositions(store.read(file, ReadingPositionsFile(), ReadingPositionsFile.serializer()), entryLimit) },
        write = { positions -> store.write(file, encodeReadingPositions(positions), ReadingPositionsFile.serializer()) },
    )
    return CellReadingPositionRepository(writeScope?.let { cell.writeBehind(it, writeDelay) } ?: cell, entryLimit)
}

internal class CellReadingPositionRepository(
    private val cell: StateCell<List<ReadingPosition>>,
    private val entryLimit: Int = MAX_READING_POSITIONS,
) : ReadingPositionRepository {
    override fun observe(): Flow<List<ReadingPosition>> = cell.data

    override suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition? =
        cell.value().firstOrNull { it.postId == postId && it.section == section }

    override suspend fun snapshot(): List<ReadingPosition> = cell.value().toList()

    override suspend fun record(position: ReadingPosition) = writing { current ->
        val previous = current.firstOrNull { it.postId == position.postId && it.section == position.section }
        when {
            previous != null && previous.lastViewedAt > position.lastViewedAt -> current
            previous != null && previous.repeatsNewest(position, current) -> current
            else -> normalizeReadingPositions(listOf(position) + current, entryLimit)
        }
    }

    private fun ReadingPosition.repeatsNewest(next: ReadingPosition, current: List<ReadingPosition>): Boolean =
        mediaNumber == next.mediaNumber &&
            next.lastViewedAt - lastViewedAt < READING_POSITION_COALESCE_MS &&
            current.firstOrNull() == this

    override suspend fun replace(positions: List<ReadingPosition>) {
        writing { normalizeReadingPositions(positions, entryLimit) }
        flush()
    }

    override suspend fun merge(positions: List<ReadingPosition>) {
        writing { current -> normalizeReadingPositions(current + positions, entryLimit) }
        flush()
    }

    override suspend fun flush() = cell.flush()

    private suspend fun writing(transform: (List<ReadingPosition>) -> List<ReadingPosition>) =
        withContext(NonCancellable) { cell.update(transform) }
}

@Serializable
internal data class ReadingPositionsFile(
    @SerialName("schemaVersion")
    val schemaVersion: Int = 1,
    @SerialName("positions")
    val positions: JsonElement = JsonArray(emptyList()),
)

@Serializable
internal data class ReadingPositionRecord(
    @SerialName("source")
    val source: String,
    @SerialName("postId")
    val postId: String,
    @SerialName("section")
    val section: String,
    @SerialName("mediaNumber")
    val mediaNumber: Int,
    @SerialName("lastViewedAt")
    val lastViewedAt: Long,
)

internal fun decodeReadingPositions(document: ReadingPositionsFile, entryLimit: Int = MAX_READING_POSITIONS): List<ReadingPosition> {
    require(document.schemaVersion == 1) { "Unsupported reading-position schema ${document.schemaVersion}" }
    val entries = requireNotNull(document.positions as? JsonArray) { "Reading positions are not a list" }
    val decoded = entries.decodeEachOrSkip<ReadingPositionRecord>().mapNotNull { record ->
        runCatching {
            ReadingPosition(
                postId = PostId(SourceKey.parseOrThrow(record.source), record.postId),
                section = RecentPostSection.valueOf(record.section),
                mediaNumber = record.mediaNumber,
                lastViewedAt = record.lastViewedAt,
            )
        }.getOrNull()
    }
    return normalizeReadingPositions(decoded, entryLimit)
}

private fun encodeReadingPositions(updated: List<ReadingPosition>) = ReadingPositionsFile(
    positions = JsonArray(updated.map { position ->
        DurableJson.encodeToJsonElement(
            ReadingPositionRecord.serializer(),
            ReadingPositionRecord(
                source = position.postId.source.id,
                postId = position.postId.sourcePostId,
                section = position.section.name,
                mediaNumber = position.mediaNumber,
                lastViewedAt = position.lastViewedAt,
            ),
        )
    }),
)

class InMemoryReadingPositionRepository :
    ReadingPositionRepository by CellReadingPositionRepository(MemoryStateCell(emptyList()))

private fun normalizeReadingPositions(positions: List<ReadingPosition>, limit: Int): List<ReadingPosition> = positions
    .sortedByDescending(ReadingPosition::lastViewedAt)
    .distinctBy { it.postId to it.section }
    .take(limit)
