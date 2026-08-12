package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.data.storage.QueryStorageRecord
import com.theoriacodex.data.storage.mutateAndPersistWithRollback
import com.theoriacodex.domain.model.Query
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FileBackedQueryRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    recoveryRegistry: LegacyJsonRecoveryRegistry = LegacyJsonRecoveryRegistry(),
) : QueryRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("query_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val queriesFlow = MutableStateFlow<Map<String, Query>>(emptyMap())

    init {
        recoveryRegistry.registerStore("Saved searches", storageFile)
        val stored = runBlocking {
            fileStore.read(
                file = storageFile,
                fallback = QueryStoreFile(),
                logicalStore = "Saved searches",
                onRecovery = recoveryRegistry::record,
            )
        }
        queriesFlow.value = stored.queries.mapValues { (_, record) -> QueryStorageCodec.decode(record) }
    }

    override fun observeAppliedQuery(modeKey: String): Flow<Query?> {
        return queriesFlow.map { it[modeKey] }
    }

    override suspend fun upsertAppliedQuery(modeKey: String, query: Query) {
        mutex.withLock {
            commitMutation { queriesFlow.value = queriesFlow.value + (modeKey to query) }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = { queriesFlow.value },
            restore = { queries -> queriesFlow.value = queries },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        val payload = QueryStoreFile(
            queries = queriesFlow.value.mapValues { (_, query) -> QueryStorageCodec.encode(query) },
        )
        fileStore.write(storageFile, payload)
    }
}

private data class QueryStoreFile(
    @field:SerializedName("queries")
    val queries: Map<String, QueryStorageRecord> = emptyMap(),
)
