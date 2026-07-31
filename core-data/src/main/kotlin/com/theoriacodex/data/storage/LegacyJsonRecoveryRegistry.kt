package com.theoriacodex.data.storage

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Application-scoped evidence for verified recovery of legacy whole-file JSON stores. */
class LegacyJsonRecoveryRegistry {
    private val mutableRecoveries = MutableStateFlow<List<CorruptionRecovery>>(emptyList())

    val recoveries: StateFlow<List<CorruptionRecovery>> = mutableRecoveries.asStateFlow()

    /** Re-discovers verified quarantines when a process restarts after the live name was removed. */
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
                val bytes = candidate.readBytes()
                check(bytes.size.toLong() == byteCount && bytes.sha256() == sha256) {
                    "Legacy JSON quarantine verification failed for ${candidate.absolutePath}"
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
