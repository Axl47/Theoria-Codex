package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.data.storage.QueryStorageRecord
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.update
import com.theoriacodex.domain.model.Query
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class FileBackedQueryRepository private constructor(repository: QueryRepository) : QueryRepository by repository {
    constructor(
        baseDirectory: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
    ) : this(fileBackedQueries(baseDirectory.resolve("query_store.json"), ioDispatcher, recoveryRegistry))
}

private fun fileBackedQueries(
    storageFile: File,
    ioDispatcher: CoroutineDispatcher,
    recoveryRegistry: FileStoreRecoveryRegistry,
): QueryRepository {
    val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    recoveryRegistry.registerStore("Saved searches", storageFile)
    val stored = runBlocking {
        fileStore.read(
            file = storageFile,
            fallback = QueryStoreFile(),
            serializer = QueryStoreFile.serializer(),
            logicalStore = "Saved searches",
            onRecovery = recoveryRegistry::record,
        )
    }
    val cell = FileStateCell(stored.queries.mapValues { (_, record) -> QueryStorageCodec.decode(record) }) { queries ->
        val payload = QueryStoreFile(queries = queries.mapValues { (_, query) -> QueryStorageCodec.encode(query) })
        fileStore.write(storageFile, payload, QueryStoreFile.serializer())
    }
    return CellQueryRepository(cell)
}

/** Applied queries by mode over a [StateCell], shared by the file-backed store and the in-memory default. */
internal class CellQueryRepository(private val cell: StateCell<Map<String, Query>>) : QueryRepository {
    override fun observeAppliedQuery(modeKey: String): Flow<Query?> = cell.data.map { it[modeKey] }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        upsertAppliedQueries(mapOf(modeKey to query))
    }

    override suspend fun upsertAppliedQueries(queries: Map<String, Query>) {
        cell.update { current -> current + queries }
    }
}

@Serializable
internal data class QueryStoreFile(
    @SerialName("queries")
    val queries: Map<String, QueryStorageRecord> = emptyMap(),
)
