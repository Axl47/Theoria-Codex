package com.theoriacodex.app.backup

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutoBackupStatus(
    val folderUri: String? = null,
    val folderName: String? = null,
    val lastSuccessEpochMs: Long? = null,
    val failure: String? = null,
    val running: Boolean = false,
) {
    val enabled: Boolean get() = folderUri != null
}

/** Device-local schedule state: a folder grant cannot travel with a backup or to another install. */
internal interface AutoBackupStore {
    fun read(): AutoBackupStatus
    fun write(status: AutoBackupStatus)
}

/** The chosen folder. A write either leaves a complete, verified file or none at all. */
internal interface AutoBackupFolder {
    fun names(): List<String>
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/**
 * Daily backup to a user-chosen folder, run when the app leaves the foreground. Library data only
 * changes while the app is in use, so that moment captures everything without background scheduling.
 */
class AutoBackupWorkflow internal constructor(
    private val store: AutoBackupStore,
    private val openFolder: (String) -> AutoBackupFolder,
    private val export: suspend () -> ByteArray,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableStatus = MutableStateFlow(store.read().copy(running = false))
    val status: StateFlow<AutoBackupStatus> = mutableStatus.asStateFlow()
    private val mutex = Mutex()

    fun onAppBackgrounded() {
        if (isDue(status.value)) launchBackup(onlyIfDue = true)
    }

    fun backUpNow(): Job = launchBackup(onlyIfDue = false)

    fun enable(folderUri: String, folderName: String?): Job {
        publish { it.copy(folderUri = folderUri, folderName = folderName, failure = null) }
        return backUpNow()
    }

    fun disable() = publish { AutoBackupStatus() }

    private fun launchBackup(onlyIfDue: Boolean): Job = scope.launch {
        mutex.withLock {
            val folderUri = status.value.folderUri ?: return@withLock
            if (onlyIfDue && !isDue(status.value)) return@withLock
            mutableStatus.update { it.copy(running = true) }
            val result = runCatchingPreservingCancellation { writeBackup(openFolder(folderUri)) }
            publish { current ->
                // Turning this off or changing folders during a run must not revive the old state.
                if (current.folderUri != folderUri) current.copy(running = false)
                else result.fold(
                    onSuccess = { current.copy(lastSuccessEpochMs = clock(), failure = null, running = false) },
                    onFailure = { current.copy(failure = it.autoBackupMessage(), running = false) },
                )
            }
        }
    }

    private suspend fun writeBackup(folder: AutoBackupFolder) {
        folder.names().filter { it.isAbandonedPart() }.forEach(folder::delete)
        folder.write(autoBackupFileName(clock()), export())
        // Only ever delete complete files this feature named, and only after a newer one is safe.
        folder.names().filter(AUTO_BACKUP_NAME::matches).sortedDescending().drop(AUTO_BACKUP_KEEP_COUNT).forEach(folder::delete)
    }

    private fun isDue(current: AutoBackupStatus): Boolean {
        if (!current.enabled || current.running) return false
        val last = current.lastSuccessEpochMs ?: return true
        return clock() - last >= AUTO_BACKUP_INTERVAL_MS
    }

    private fun publish(transform: (AutoBackupStatus) -> AutoBackupStatus) {
        mutableStatus.update(transform)
        store.write(status.value.copy(running = false))
    }
}

internal fun autoBackupFileName(epochMs: Long): String =
    "$AUTO_BACKUP_PREFIX${AUTO_BACKUP_STAMP.format(Instant.ofEpochMilli(epochMs))}.json"

private fun String.isAbandonedPart(): Boolean =
    endsWith(AUTO_BACKUP_PART_SUFFIX) && AUTO_BACKUP_NAME.matches(removeSuffix(AUTO_BACKUP_PART_SUFFIX))

private fun Throwable.autoBackupMessage(): String = when (this) {
    is SecurityException, is java.io.FileNotFoundException -> "Theoria lost access to the backup folder. Choose it again."
    is IllegalArgumentException -> message ?: AUTO_BACKUP_FAILED
    else -> AUTO_BACKUP_FAILED
}

internal const val AUTO_BACKUP_PART_SUFFIX = ".part"
internal const val AUTO_BACKUP_KEEP_COUNT = 7

// Shorter than a day so a habit of opening the app at about the same time still backs up daily.
internal const val AUTO_BACKUP_INTERVAL_MS = 20L * 60L * 60L * 1000L

private const val AUTO_BACKUP_FAILED = "Could not save the automatic backup."
private const val AUTO_BACKUP_PREFIX = "theoria-auto-backup-"
private val AUTO_BACKUP_NAME = Regex("""theoria-auto-backup-\d{8}-\d{6}\.json""")
private val AUTO_BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
