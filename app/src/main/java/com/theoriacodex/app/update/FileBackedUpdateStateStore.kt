package com.theoriacodex.app.update

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FileBackedUpdateStateStore(
    private val file: File,
    private val fileStore: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
) : UpdateStateStore {
    private val mutex = Mutex()
    private var cachedSnapshot: UpdateStateSnapshot? = null

    init {
        recoveryRegistry.registerStore("Updater state", file)
    }

    override suspend fun snapshot(): UpdateStateSnapshot = mutex.withLock {
        readLocked()
    }

    override suspend fun update(transform: (UpdateStateSnapshot) -> UpdateStateSnapshot) {
        mutex.withLock {
            val replacement = transform(readLocked())
            fileStore.write(file, replacement, UpdateStateSnapshot.serializer())
            cachedSnapshot = replacement
        }
    }

    private suspend fun readLocked(): UpdateStateSnapshot {
        cachedSnapshot?.let { snapshot -> return snapshot }
        return fileStore.read(
            file = file,
            fallback = UpdateStateSnapshot(),
            serializer = UpdateStateSnapshot.serializer(),
            logicalStore = "Updater state",
            onRecovery = recoveryRegistry::record,
        ).also { snapshot ->
            cachedSnapshot = snapshot
        }
    }
}
