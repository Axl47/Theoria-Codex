package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.data.storage.SavedSearchSnapshot
import com.theoriacodex.data.storage.SavedSearchSnapshotSerializer
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val MAX_SAVED_SEARCHES = 200
const val MAX_SAVED_SEARCH_NAME_LENGTH = 80

data class SavedSearchEntry(
    val id: String,
    val name: String,
    val search: RecentSearchEntry,
    val savedAtEpochMs: Long,
)

/** The saved search that stands for [search]: the same query hash and the same stored payload, whatever its name. */
fun List<SavedSearchEntry>.savedFor(search: RecentSearchEntry): SavedSearchEntry? {
    val payload by lazy { RecentSearchPayloadCodec.encodeJson(search) }
    return firstOrNull { it.search.queryHash == search.queryHash && RecentSearchPayloadCodec.encodeJson(it.search) == payload }
}

/** Named searches are device-wide, like Recents, and independent of history clearing or pruning. */
interface SavedSearchRepository {
    fun observeSavedSearches(): Flow<List<SavedSearchEntry>>
    suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry
    suspend fun rename(id: String, name: String)
    suspend fun remove(id: String)

    /** Validates the complete backup before atomically replacing the saved-search library. */
    suspend fun replaceAll(entries: List<SavedSearchEntry>)

    /** Adds imported pins atomically, replacing only matching imported IDs and rejecting overflow. */
    suspend fun merge(entries: List<SavedSearchEntry>)
}

class FileBackedSavedSearchRepository private constructor(repository: SavedSearchRepository) :
    SavedSearchRepository by repository {
    constructor(
        baseDirectory: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
        now: () -> Long = System::currentTimeMillis,
        newId: () -> String = { UUID.randomUUID().toString() },
        /** Names the per-source searches a saved For You mix splits into; see [withForYouSearchesConverted]. */
        sourceName: (SourceKey) -> String = SourceKey::id,
    ) : this(fileBackedSavedSearches(baseDirectory, ioDispatcher, recoveryRegistry, now, newId, sourceName))
}

class InMemorySavedSearchRepository(
    initialEntries: List<SavedSearchEntry> = emptyList(),
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
) : SavedSearchRepository by MutableSavedSearchRepository(initialEntries, now, newId, SourceKey::id, writeSnapshot = {})

private fun fileBackedSavedSearches(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher,
    recoveryRegistry: FileStoreRecoveryRegistry,
    now: () -> Long,
    newId: () -> String,
    sourceName: (SourceKey) -> String,
): SavedSearchRepository {
    val storageFile = baseDirectory.resolve("pinned_searches.json")
    val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    recoveryRegistry.registerStore("Pinned searches", storageFile)
    val snapshot = runBlocking {
        fileStore.read(
            file = storageFile,
            fallback = SavedSearchSnapshot(emptyList()),
            serializer = SavedSearchSnapshotSerializer,
            logicalStore = "Pinned searches",
            onRecovery = recoveryRegistry::record,
        )
    }
    val entries = snapshot.entries.withForYouSearchesConverted(sourceName)
    if (entries != snapshot.entries) {
        runBlocking { fileStore.write(storageFile, SavedSearchSnapshot.validated(entries), SavedSearchSnapshotSerializer) }
    }
    return MutableSavedSearchRepository(entries, now, newId, sourceName) {
        fileStore.write(storageFile, it, SavedSearchSnapshotSerializer)
    }
}

/** Converts saved For You searches ([withForYouSearchesConverted]) when the library loads and when a backup lands. */
private class MutableSavedSearchRepository(
    initialEntries: List<SavedSearchEntry>,
    private val now: () -> Long,
    private val newId: () -> String,
    private val sourceName: (SourceKey) -> String,
    private val writeSnapshot: suspend (SavedSearchSnapshot) -> Unit,
) : SavedSearchRepository {
    private val mutex = Mutex()
    private val savedSearches = MutableStateFlow(
        SavedSearchSnapshot.validated(initialEntries.withForYouSearchesConverted(sourceName)).entries,
    )

    override fun observeSavedSearches(): Flow<List<SavedSearchEntry>> = savedSearches.asStateFlow()

    override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry = mutex.withLock {
        val normalizedName = validateSavedSearchName(name)
        val existing = savedSearches.value.savedFor(search)
        require(existing != null || savedSearches.value.size < MAX_SAVED_SEARCHES) {
            "You can pin up to $MAX_SAVED_SEARCHES searches. Remove one before adding another."
        }
        val entry = SavedSearchEntry(
            id = existing?.id ?: newId(),
            name = normalizedName,
            search = search,
            savedAtEpochMs = existing?.savedAtEpochMs ?: now(),
        )
        val next = if (existing == null) listOf(entry) + savedSearches.value
        else savedSearches.value.map { if (it.id == existing.id) entry else it }
        persist(next)
        entry
    }

    override suspend fun rename(id: String, name: String) = mutex.withLock {
        val normalizedName = validateSavedSearchName(name)
        require(savedSearches.value.any { it.id == id }) { "This saved search is no longer available." }
        persist(savedSearches.value.map { if (it.id == id) it.copy(name = normalizedName) else it })
    }

    override suspend fun remove(id: String) = mutex.withLock {
        val next = savedSearches.value.filterNot { it.id == id }
        if (next.size != savedSearches.value.size) persist(next)
    }

    override suspend fun replaceAll(entries: List<SavedSearchEntry>) = mutex.withLock {
        persist(entries.withForYouSearchesConverted(sourceName))
    }

    override suspend fun merge(entries: List<SavedSearchEntry>) = mutex.withLock {
        // Converted together with the existing searches, so a split mix takes only slots that are still free.
        val importedIds = entries.mapTo(mutableSetOf(), SavedSearchEntry::id)
        persist((entries + savedSearches.value.filterNot { it.id in importedIds }).withForYouSearchesConverted(sourceName))
    }

    private suspend fun persist(entries: List<SavedSearchEntry>) {
        val snapshot = SavedSearchSnapshot.validated(entries)
        writeSnapshot(snapshot)
        savedSearches.value = snapshot.entries
    }
}

internal fun validateSavedSearchName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Give this search a name." }
    require(trimmed.length <= MAX_SAVED_SEARCH_NAME_LENGTH) {
        "Search names can contain up to $MAX_SAVED_SEARCH_NAME_LENGTH characters."
    }
    return trimmed
}
