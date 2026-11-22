package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.decodeEachOrSkip
import com.theoriacodex.data.storage.update
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EXTENSION_SOURCE_ID_PREFIX
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** The reading mode a user chose for one gallery; galleries without one follow the global default. */
interface GalleryReadingModeRepository {
    fun observe(): Flow<Map<PostId, GalleryReadingMode>>
    suspend fun set(postId: PostId, mode: GalleryReadingMode)
}

const val GALLERY_READING_MODES_FILE_NAME = "gallery_reading_modes.json"
const val MAX_GALLERY_READING_MODES = 2_000

/** The most recent choices are kept; the oldest beyond [entryLimit] fall back to the default. */
class FileBackedGalleryReadingModeRepository private constructor(repository: GalleryReadingModeRepository) :
    GalleryReadingModeRepository by repository {
    constructor(
        baseDirectory: File,
        store: AtomicJsonFileStore = AtomicJsonFileStore(),
        entryLimit: Int = MAX_GALLERY_READING_MODES,
    ) : this(fileBackedGalleryReadingModes(baseDirectory.resolve(GALLERY_READING_MODES_FILE_NAME), store, entryLimit))
}

/** Fixture/default owner; production supplies the file-backed repository. */
class InMemoryGalleryReadingModeRepository :
    GalleryReadingModeRepository by CellGalleryReadingModeRepository(MemoryStateCell(emptyList()))

private fun fileBackedGalleryReadingModes(file: File, store: AtomicJsonFileStore, entryLimit: Int): GalleryReadingModeRepository {
    require(entryLimit > 0)
    val cell = FileStateCell(
        load = { decodeGalleryReadingModes(store.read(file, GalleryReadingModesFile(), GalleryReadingModesFile.serializer()), entryLimit) },
        write = { choices -> store.write(file, encodeGalleryReadingModes(choices), GalleryReadingModesFile.serializer()) },
    )
    return CellGalleryReadingModeRepository(cell, entryLimit)
}

internal data class GalleryReadingModeChoice(val postId: PostId, val mode: GalleryReadingMode)

/** Choices newest first over a [StateCell]; setting one moves it to the front. */
internal class CellGalleryReadingModeRepository(
    private val cell: StateCell<List<GalleryReadingModeChoice>>,
    private val entryLimit: Int = MAX_GALLERY_READING_MODES,
) : GalleryReadingModeRepository {
    override fun observe(): Flow<Map<PostId, GalleryReadingMode>> =
        cell.data.map { choices -> choices.associate { it.postId to it.mode } }

    override suspend fun set(postId: PostId, mode: GalleryReadingMode) = withContext(Dispatchers.Default) {
        cell.update { current -> normalizeGalleryReadingModes(listOf(GalleryReadingModeChoice(postId, mode)) + current, entryLimit) }
    }
}

/** gallery_reading_modes.json. The list stays raw JSON so a newer schema is rejected before its shape is read. */
@Serializable
internal data class GalleryReadingModesFile(
    @SerialName("schemaVersion")
    val schemaVersion: Int = 1,
    @SerialName("choices")
    val choices: JsonElement = JsonArray(emptyList()),
)

@Serializable
internal data class GalleryReadingModeRecord(
    @SerialName("source")
    val source: String,
    @SerialName("postId")
    val postId: String,
    @SerialName("mode")
    val mode: String,
)

/**
 * A newer schema fails rather than being read as, or rewritten to, this one; one unreadable entry is skipped. So is one
 * for an extension source: the extension spike kept series here as posts, and a series now keeps its mode with itself.
 */
internal fun decodeGalleryReadingModes(
    document: GalleryReadingModesFile,
    entryLimit: Int = MAX_GALLERY_READING_MODES,
): List<GalleryReadingModeChoice> {
    require(document.schemaVersion == 1) { "Unsupported gallery reading mode schema ${document.schemaVersion}" }
    val entries = requireNotNull(document.choices as? JsonArray) { "Gallery reading modes are not a list" }
    val decoded = entries.decodeEachOrSkip<GalleryReadingModeRecord>().mapNotNull { record ->
        if (record.source.startsWith(EXTENSION_SOURCE_ID_PREFIX)) return@mapNotNull null
        runCatching {
            GalleryReadingModeChoice(PostId(SourceKey.parseOrThrow(record.source), record.postId), GalleryReadingMode.valueOf(record.mode))
        }.getOrNull()
    }
    return normalizeGalleryReadingModes(decoded, entryLimit)
}

private fun encodeGalleryReadingModes(choices: List<GalleryReadingModeChoice>) = GalleryReadingModesFile(
    choices = JsonArray(choices.map { choice ->
        DurableJson.encodeToJsonElement(
            GalleryReadingModeRecord.serializer(),
            GalleryReadingModeRecord(choice.postId.source.id, choice.postId.sourcePostId, choice.mode.name),
        )
    }),
)

private fun normalizeGalleryReadingModes(choices: List<GalleryReadingModeChoice>, limit: Int) =
    choices.distinctBy { it.postId }.take(limit)
