package com.theoriacodex.data.storage

import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Application-scoped record of stores, file stores and DataStores alike, that set an unreadable file aside with
 * [setAsideCorruptFile] and started empty. Settings lists them under Recovered storage.
 */
class FileStoreRecoveryRegistry {
    private val mutableRecoveries = MutableStateFlow<List<CorruptionRecovery>>(emptyList())

    val recoveries: StateFlow<List<CorruptionRecovery>> = mutableRecoveries.asStateFlow()

    /**
     * Re-lists files [setAsideCorruptFile] left beside [liveFile] in an earlier process. A file whose bytes no longer
     * match its name is left out of the list but never blocks the store: it is a copy of data that was already
     * unreadable, and the live file decides on its own whether the store opens. Only files whose size matches their
     * name are read to check the checksum.
     */
    fun registerStore(logicalStore: String, liveFile: File) {
        val prefix = "${liveFile.name}.corrupt-"
        val parent = liveFile.parentFile ?: return
        if (Files.notExists(parent.toPath())) return
        val candidates = Files.newDirectoryStream(parent.toPath()).use { entries ->
            entries.map { path -> path.toFile() }
        }
        candidates.asSequence()
            .filter { candidate -> candidate.isFile && candidate.name.startsWith(prefix) }
            .sortedBy(File::getName)
            .forEach { candidate ->
                val identity = candidate.name.removePrefix(prefix)
                val separator = identity.indexOf('-')
                if (separator <= 0) return@forEach
                val byteCount = identity.substring(0, separator).toLongOrNull() ?: return@forEach
                val sha256 = identity.substring(separator + 1)
                if (sha256.length != 64 || sha256.any { character -> character !in '0'..'9' && character !in 'a'..'f' }) {
                    return@forEach
                }
                if (candidate.length() != byteCount || !candidate.hasSha256(sha256)) {
                    Diagnostics.report("storage", "${candidate.name} no longer matches its name; not listed")
                    return@forEach
                }
                record(
                    CorruptionRecovery(
                        reason = "Unreadable local data was preserved before this store was reset",
                        backupPath = candidate.absolutePath,
                        logicalStore = logicalStore,
                        logicalFile = liveFile.name,
                        sha256 = sha256,
                        byteCount = byteCount,
                    )
                )
            }
    }

    fun record(recovery: CorruptionRecovery) {
        mutableRecoveries.update { current ->
            if (current.any { existing -> existing.backupPath == recovery.backupPath }) {
                current
            } else {
                current + recovery
            }
        }
    }
}

/** False when the file changed since it was named after [sha256] or can no longer be read. */
private fun File.hasSha256(sha256: String): Boolean = try {
    readBytes().sha256() == sha256
} catch (_: IOException) {
    false
}

/**
 * Sets [file], whose current contents are [bytes], aside as `<name>.corrupt-<size>-<sha256>` and returns the recovery
 * to report. Every store that resets an unreadable file goes through here. The file is renamed, which needs no free
 * space and leaves either the live name or the set-aside name on disk if the process dies. The name is
 * content-addressed, so a later failure never replaces an earlier set-aside file, and
 * [FileStoreRecoveryRegistry.registerStore] finds it again after a restart. Throws when the file cannot be set aside;
 * the live file then keeps its bytes, and callers must not reset it.
 */
internal fun setAsideCorruptFile(
    file: File,
    bytes: ByteArray,
    logicalStore: String,
    reason: String,
    operations: AtomicFileOperations = DefaultAtomicFileOperations,
): CorruptionRecovery {
    val sha256 = bytes.sha256()
    val setAside = file.resolveSibling("${file.name}.corrupt-${bytes.size}-$sha256")
    try {
        operations.moveWithoutReplace(file, setAside)
    } catch (_: FileAlreadyExistsException) {
        // The same bytes were set aside before, by a recovery interrupted before its reset was written.
        check(operations.readBytes(setAside).contentEquals(bytes)) {
            "Refusing to replace a different quarantine at ${setAside.absolutePath}"
        }
        check(operations.delete(file) || !operations.exists(file)) {
            "Could not remove quarantined file ${file.absolutePath}"
        }
    }
    return CorruptionRecovery(
        reason = reason,
        backupPath = setAside.absolutePath,
        logicalStore = logicalStore,
        logicalFile = file.name,
        sha256 = sha256,
        byteCount = bytes.size.toLong(),
    )
}
