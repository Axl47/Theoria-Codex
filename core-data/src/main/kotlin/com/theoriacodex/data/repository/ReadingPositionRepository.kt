package com.theoriacodex.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition?
    suspend fun record(position: ReadingPosition)
    suspend fun snapshot(): List<ReadingPosition>
    suspend fun replace(positions: List<ReadingPosition>)
    suspend fun merge(positions: List<ReadingPosition>)
}

const val READING_POSITIONS_FILE_NAME = "reading_positions.json"
const val MAX_READING_POSITIONS = 2_000

/** Uses explicit wire fields, so release obfuscation cannot change reading-position documents. */
class FileBackedReadingPositionRepository(
    baseDirectory: File,
    private val store: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val entryLimit: Int = MAX_READING_POSITIONS,
) : ReadingPositionRepository {
    private val file = baseDirectory.resolve(READING_POSITIONS_FILE_NAME)
    private val mutex = Mutex()
    private var positions: List<ReadingPosition>? = null

    init {
        require(entryLimit > 0)
    }

    override suspend fun get(postId: PostId, section: RecentPostSection): ReadingPosition? = mutex.withLock {
        load().firstOrNull { it.postId == postId && it.section == section }
    }

    override suspend fun snapshot(): List<ReadingPosition> = mutex.withLock { load().toList() }

    override suspend fun record(position: ReadingPosition) = mutex.withLock {
        val current = load()
        val previous = current.firstOrNull { it.postId == position.postId && it.section == position.section }
        if (previous != null && previous.lastViewedAt > position.lastViewedAt) return@withLock
        persist(normalizeReadingPositions(listOf(position) + current, entryLimit))
    }

    override suspend fun replace(positions: List<ReadingPosition>) = mutex.withLock {
        persist(normalizeReadingPositions(positions, entryLimit))
    }

    override suspend fun merge(positions: List<ReadingPosition>) = mutex.withLock {
        persist(normalizeReadingPositions(load() + positions, entryLimit))
    }

    private suspend fun load(): List<ReadingPosition> {
        positions?.let { return it }
        val document = store.read(file, JsonObject())
        val schema = document.get("schemaVersion")?.asInt ?: 1
        require(schema == 1) { "Unsupported reading-position schema $schema" }
        val decoded = document.getAsJsonArray("positions")?.mapNotNull { element ->
            runCatching {
                val record = element.asJsonObject
                ReadingPosition(
                    postId = PostId(SourceKey.valueOf(record.get("source").asString), record.get("postId").asString),
                    section = RecentPostSection.valueOf(record.get("section").asString),
                    mediaNumber = record.get("mediaNumber").asInt,
                    lastViewedAt = record.get("lastViewedAt").asLong,
                )
            }.getOrNull()
        }.orEmpty()
        return normalizeReadingPositions(decoded, entryLimit).also { positions = it }
    }

    private suspend fun persist(updated: List<ReadingPosition>) {
        val document = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("positions", JsonArray().apply {
                updated.forEach { position ->
                    add(JsonObject().apply {
                        addProperty("source", position.postId.source.name)
                        addProperty("postId", position.postId.sourcePostId)
                        addProperty("section", position.section.name)
                        addProperty("mediaNumber", position.mediaNumber)
                        addProperty("lastViewedAt", position.lastViewedAt)
                    })
                }
            })
        }
        store.write(file, document)
        positions = updated
    }
}

/** Fixture/default owner; production supplies the file-backed repository. */
class InMemoryReadingPositionRepository : ReadingPositionRepository {
    private val mutex = Mutex()
    private var positions = emptyList<ReadingPosition>()

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
