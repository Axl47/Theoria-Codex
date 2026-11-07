package com.theoriacodex.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/**
 * Uses explicit wire fields, so release obfuscation cannot change reading-position documents.
 * The store writes compact JSON because the Viewer rewrites this file on every media change.
 */
class FileBackedReadingPositionRepository(
    baseDirectory: File,
    private val store: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val entryLimit: Int = MAX_READING_POSITIONS,
) : ReadingPositionRepository {
    private val file = baseDirectory.resolve(READING_POSITIONS_FILE_NAME)
    private val mutex = Mutex()
    private var positions: List<ReadingPosition>? = null
    private val updates = MutableStateFlow<List<ReadingPosition>>(emptyList())

    override fun observe(): Flow<List<ReadingPosition>> = flow {
        mutex.withLock { load() }
        emitAll(updates)
    }

    init {
        require(entryLimit > 0)
    }

    override suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition? = mutex.withLock {
        load().firstOrNull { it.postId == postId && it.section == section }
    }

    override suspend fun snapshot(): List<ReadingPosition> = mutex.withLock { load().toList() }

    override suspend fun record(position: ReadingPosition) = writing {
        val current = load()
        val previous = current.firstOrNull { it.postId == position.postId && it.section == position.section }
        if (previous != null && previous.lastViewedAt > position.lastViewedAt) return@writing
        if (previous != null && previous.repeatsNewest(position, current)) return@writing
        val updated = normalizeReadingPositions(listOf(position) + current, entryLimit)
        if (updated != current) persist(updated)
    }

    private fun ReadingPosition.repeatsNewest(next: ReadingPosition, current: List<ReadingPosition>): Boolean =
        mediaNumber == next.mediaNumber &&
            next.lastViewedAt - lastViewedAt < READING_POSITION_COALESCE_MS &&
            current.firstOrNull() == this

    override suspend fun replace(positions: List<ReadingPosition>) = writing {
        persist(normalizeReadingPositions(positions, entryLimit))
    }

    override suspend fun merge(positions: List<ReadingPosition>) = writing {
        persist(normalizeReadingPositions(load() + positions, entryLimit))
    }

    /** Each write re-sorts and re-encodes the whole list, which must not run on a caller's main thread. */
    private suspend fun writing(block: suspend () -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock { block() }
    }

    private suspend fun load(): List<ReadingPosition> {
        positions?.let { return it }
        val document = store.read(file, JsonObject())
        return decodeReadingPositions(document, entryLimit).also {
            positions = it
            updates.value = it
        }
    }

    private suspend fun persist(updated: List<ReadingPosition>) {
        store.write(file, encodeReadingPositions(updated))
        positions = updated
        updates.value = updated
    }
}

internal fun decodeReadingPositions(document: JsonObject, entryLimit: Int = MAX_READING_POSITIONS): List<ReadingPosition> {
    val schema = document.get("schemaVersion")?.asInt ?: 1
    require(schema == 1) { "Unsupported reading-position schema $schema" }
    val decoded = document.getAsJsonArray("positions")?.mapNotNull { element ->
        runCatching {
            val record = element.asJsonObject
            ReadingPosition(
                postId = PostId(SourceKey.parseOrThrow(record.get("source").asString), record.get("postId").asString),
                section = RecentPostSection.valueOf(record.get("section").asString),
                mediaNumber = record.get("mediaNumber").asInt,
                lastViewedAt = record.get("lastViewedAt").asLong,
            )
        }.getOrNull()
    }.orEmpty()
    return normalizeReadingPositions(decoded, entryLimit)
}

private fun encodeReadingPositions(updated: List<ReadingPosition>): JsonObject = JsonObject().apply {
    addProperty("schemaVersion", 1)
    add("positions", JsonArray().apply {
        updated.forEach { position ->
            add(JsonObject().apply {
                addProperty("source", position.postId.source.id)
                addProperty("postId", position.postId.sourcePostId)
                addProperty("section", position.section.name)
                addProperty("mediaNumber", position.mediaNumber)
                addProperty("lastViewedAt", position.lastViewedAt)
            })
        }
    })
}

/** Fixture/default owner; production supplies the file-backed repository. */
class InMemoryReadingPositionRepository : ReadingPositionRepository {
    private val mutex = Mutex()
    private val updates = MutableStateFlow<List<ReadingPosition>>(emptyList())
    private var positions: List<ReadingPosition>
        get() = updates.value
        set(value) { updates.value = value }

    override fun observe(): Flow<List<ReadingPosition>> = updates

    override suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition? = mutex.withLock {
        positions.firstOrNull { it.postId == postId && it.section == section }
    }

    override suspend fun snapshot(): List<ReadingPosition> = mutex.withLock { positions.toList() }

    override suspend fun record(position: ReadingPosition) = mutex.withLock {
        positions = normalizeReadingPositions(listOf(position) + positions, MAX_READING_POSITIONS)
    }

    override suspend fun replace(positions: List<ReadingPosition>) = mutex.withLock {
        this.positions = normalizeReadingPositions(positions, MAX_READING_POSITIONS)
    }

    override suspend fun merge(positions: List<ReadingPosition>) = mutex.withLock {
        this.positions = normalizeReadingPositions(this.positions + positions, MAX_READING_POSITIONS)
    }
}

private fun normalizeReadingPositions(positions: List<ReadingPosition>, limit: Int): List<ReadingPosition> = positions
    .filter { it.section != RecentPostSection.FYP }
    .sortedByDescending(ReadingPosition::lastViewedAt)
    .distinctBy { it.postId to it.section }
    .take(limit)
