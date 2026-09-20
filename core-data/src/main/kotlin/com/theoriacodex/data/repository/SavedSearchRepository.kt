package com.theoriacodex.data.repository

import com.google.gson.GsonBuilder
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.data.storage.SavedSearchSnapshot
import com.theoriacodex.data.storage.SavedSearchSnapshotAdapter
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
        recoveryRegistry: LegacyJsonRecoveryRegistry = LegacyJsonRecoveryRegistry(),
        now: () -> Long = System::currentTimeMillis,
        newId: () -> String = { UUID.randomUUID().toString() },
    ) : this(fileBackedSavedSearches(baseDirectory, ioDispatcher, recoveryRegistry, now, newId))
}

class InMemorySavedSearchRepository(
    initialEntries: List<SavedSearchEntry> = emptyList(),
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
) : SavedSearchRepository by MutableSavedSearchRepository(initialEntries, now, newId, writeSnapshot = {})

private fun fileBackedSavedSearches(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher,
    recoveryRegistry: LegacyJsonRecoveryRegistry,
    now: () -> Long,
    newId: () -> String,
): SavedSearchRepository {
    val storageFile = baseDirectory.resolve("pinned_searches.json")
    val fileStore = AtomicJsonFileStore(
        ioDispatcher = ioDispatcher,
        gson = GsonBuilder()
            .registerTypeAdapter(SavedSearchSnapshot::class.java, SavedSearchSnapshotAdapter())
            .create(),
    )
    recoveryRegistry.registerStore("Pinned searches", storageFile)
    val snapshot = runBlocking {
        fileStore.read(
            file = storageFile,
            fallback = SavedSearchSnapshot(emptyList()),
            logicalStore = "Pinned searches",
            onRecovery = recoveryRegistry::record,
        )
    }
    return MutableSavedSearchRepository(snapshot.entries, now, newId) { fileStore.write(storageFile, it) }
}

private class MutableSavedSearchRepository(
    initialEntries: List<SavedSearchEntry>,
    private val now: () -> Long,
    private val newId: () -> String,
    private val writeSnapshot: suspend (SavedSearchSnapshot) -> Unit,
) : SavedSearchRepository {
    private val mutex = Mutex()
    private val savedSearches = MutableStateFlow(SavedSearchSnapshot.validated(initialEntries).entries)

    override fun observeSavedSearches(): Flow<List<SavedSearchEntry>> = savedSearches.asStateFlow()

    override suspend fun save(name: String, search: RecentSearchEntry): SavedSearchEntry = mutex.withLock {
        val normalizedName = validateSavedSearchName(name)
        val payload = RecentSearchPayloadCodec.encodeJson(search)
        val existing = savedSearches.value.firstOrNull { entry ->
            entry.search.queryHash == search.queryHash && RecentSearchPayloadCodec.encodeJson(entry.search) == payload
        }
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
        persist(entries)
    }

    override suspend fun merge(entries: List<SavedSearchEntry>) = mutex.withLock {
        val imported = SavedSearchSnapshot.validated(entries).entries
        val importedIds = imported.mapTo(mutableSetOf(), SavedSearchEntry::id)
        persist(imported + savedSearches.value.filterNot { it.id in importedIds })
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
