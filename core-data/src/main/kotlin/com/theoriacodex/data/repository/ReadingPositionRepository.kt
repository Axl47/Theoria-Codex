package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.decodeEachOrSkip
import com.theoriacodex.data.storage.update
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Last visible media, independent of Recents' monotonic highest-media achievement. */
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
}

const val READING_POSITIONS_FILE_NAME = "reading_positions.json"
const val MAX_READING_POSITIONS = 2_000

/**
 * A repeat of the newest position for the same media inside this window keeps the stored timestamp.
 * The Viewer reports one swipe through both its visible-post and media-progress callbacks; relative
 * "watched" labels have minute granularity, so a few seconds of timestamp lag is not visible.
 */
internal const val READING_POSITION_COALESCE_MS = 5_000L

/** The store writes compact JSON because the Viewer rewrites this file on every media change. */
class FileBackedReadingPositionRepository private constructor(repository: ReadingPositionRepository) :
    ReadingPositionRepository by repository {
    constructor(
        baseDirectory: File,
        store: AtomicJsonFileStore = AtomicJsonFileStore(),
        entryLimit: Int = MAX_READING_POSITIONS,
    ) : this(fileBackedReadingPositions(baseDirectory.resolve(READING_POSITIONS_FILE_NAME), store, entryLimit))
}

private fun fileBackedReadingPositions(file: File, store: AtomicJsonFileStore, entryLimit: Int): ReadingPositionRepository {
    require(entryLimit > 0)
    val cell = FileStateCell(
        load = { decodeReadingPositions(store.read(file, ReadingPositionsFile(), ReadingPositionsFile.serializer()), entryLimit) },
        write = { positions -> store.write(file, encodeReadingPositions(positions), ReadingPositionsFile.serializer()) },
    )
    return CellReadingPositionRepository(cell, entryLimit)
}

/**
 * Positions over a [StateCell], shared by the file-backed store and the in-memory default. A position older than the
 * stored one for the same post is ignored, and a repeat of the newest one inside [READING_POSITION_COALESCE_MS] keeps
 * the stored timestamp.
 */
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

    override suspend fun replace(positions: List<ReadingPosition>) = writing {
        normalizeReadingPositions(positions, entryLimit)
    }

    override suspend fun merge(positions: List<ReadingPosition>) = writing { current ->
        normalizeReadingPositions(current + positions, entryLimit)
    }

    /** Each write re-sorts and re-encodes the whole list, which must not run on a caller's main thread. */
    private suspend fun writing(transform: (List<ReadingPosition>) -> List<ReadingPosition>) =
        withContext(Dispatchers.Default) { cell.update(transform) }
}

/**
 * reading_positions.json. The list stays raw JSON so a newer schema is rejected before its shape is read, and one
 * unreadable entry is skipped instead of failing the file.
 */
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

/** A newer schema fails rather than being read as, or rewritten to, this one. */
internal fun decodeReadingPositions(document: ReadingPositionsFile, entryLimit: Int = MAX_READING_POSITIONS): List<ReadingPosition> {
    require(document.schemaVersion == 1) { "Unsupported reading-position schema ${document.schemaVersion}" }
    val entries = requireNotNull(document.positions as? JsonArray) { "Reading positions are not a list" }
    val decoded = entries.decodeEachOrSkip<ReadingPositionRecord>().mapNotNull { record ->
        // An unknown source or section, or an id the domain rejects, also skips only its own entry.
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

/** Fixture/default owner; production supplies the file-backed repository. */
class InMemoryReadingPositionRepository :
    ReadingPositionRepository by CellReadingPositionRepository(MemoryStateCell(emptyList()))

private fun normalizeReadingPositions(positions: List<ReadingPosition>, limit: Int): List<ReadingPosition> = positions
    .sortedByDescending(ReadingPosition::lastViewedAt)
    .distinctBy { it.postId to it.section }
    .take(limit)
