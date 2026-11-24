package com.theoriacodex.app.backup

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.source.displayName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.ProfileBackupService
import com.theoriacodex.data.repository.ProfileRestoreResult
import com.theoriacodex.data.repository.ProfileRestorePendingException
import com.theoriacodex.data.repository.BackupPreview
import com.theoriacodex.data.repository.SeriesImportPreview
import com.theoriacodex.data.repository.SeriesImportResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface BackupActions {
    suspend fun export(includeRecents: Boolean): ByteArray
    suspend fun preview(bytes: ByteArray): BackupPreview
    suspend fun restore(bytes: ByteArray, applyPreferences: Boolean): ProfileRestoreResult
    suspend fun importSeries(bytes: ByteArray): SeriesImportResult
}

class ProfileBackupActions(private val service: ProfileBackupService) : BackupActions {
    override suspend fun export(includeRecents: Boolean) = service.export(includeRecents)
    override suspend fun preview(bytes: ByteArray) = service.preview(bytes)
    override suspend fun restore(bytes: ByteArray, applyPreferences: Boolean) = service.restore(bytes, applyPreferences)
    override suspend fun importSeries(bytes: ByteArray) = service.importSeries(bytes)
}

enum class BackupOperation { EXPORT, READ, RESTORE }

data class BackupUiState(
    val operation: BackupOperation? = null,
    val preview: BackupPreview? = null,
    val applyPreferences: Boolean = false,
    val includeRecents: Boolean = true,
) {
    val busy: Boolean get() = operation != null
}

/** Activity-owned jobs and selected bytes survive UI recreation without reopening the document. */
class BackupViewModel(
    private val actions: BackupActions,
    private val documents: BackupDocuments,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()
    private var selectedBytes: ByteArray? = null

    fun applyPreferences(apply: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(applyPreferences = apply) }
    }

    /** Whether the next manual backup carries Recents, reading positions and statistics. */
    fun includeRecents(include: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(includeRecents = include) }
    }

    fun dismissDialog() {
        if (state.value.busy) return
        selectedBytes = null
        mutableState.update { it.copy(preview = null, applyPreferences = false) }
    }

    /** Writes a backup to [document] (null when picking was cancelled), with Recents and statistics if chosen. */
    fun export(document: String?) {
        if (document == null || state.value.busy) return
        val includeRecents = state.value.includeRecents
        mutableState.update { it.copy(operation = BackupOperation.EXPORT) }
        viewModelScope.launch {
            val result = runCatchingPreservingCancellation {
                withContext(ioDispatcher) { writeExport(document, includeRecents) }
            }
            mutableState.update { it.copy(operation = null) }
            messages.send(result.fold(
                onSuccess = { "Backup saved." },
                onFailure = { it.backupMessage("Could not save the backup. Please try again.") },
            ))
        }
    }

    fun import(document: String?) {
        if (document == null || state.value.busy) return
        selectedBytes = null
        mutableState.update {
            it.copy(operation = BackupOperation.READ, preview = null, applyPreferences = false)
        }
        viewModelScope.launch {
            val result = runCatchingPreservingCancellation {
                withContext(ioDispatcher) {
                    val bytes = documents.read(document)
                    bytes to actions.preview(bytes)
                }
            }
            result.fold(
                onSuccess = { (bytes, preview) ->
                    selectedBytes = bytes
                    mutableState.update { it.copy(operation = null, preview = preview) }
                },
                onFailure = { failure ->
                    mutableState.update { it.copy(operation = null) }
                    messages.send(failure.backupMessage("Could not open this backup. Please choose a valid backup file."))
                },
            )
        }
    }

    fun restore() {
        val bytes = selectedBytes ?: return
        val preview = state.value.preview
        if (state.value.busy || preview == null) return
        val applyPreferences = state.value.applyPreferences
        mutableState.update { it.copy(operation = BackupOperation.RESTORE) }
        viewModelScope.launch {
            val result = runCatchingPreservingCancellation {
                withContext(ioDispatcher) {
                    when (preview) {
                        is BackupPreview.Profiles -> actions.restore(bytes, applyPreferences).message()
                        is BackupPreview.Series -> actions.importSeries(bytes).message()
                    }
                }
            }
            mutableState.update { it.copy(operation = null) }
            result.fold(
                onSuccess = { message ->
                    dismissDialog()
                    messages.send(message)
                },
                onFailure = { failure ->
                    messages.send(failure.backupMessage("Could not restore the backup. Please try again."))
                },
            )
        }
    }

    private suspend fun writeExport(document: String, includeRecents: Boolean) {
        var completed = false
        try {
            documents.write(document, actions.export(includeRecents))
            completed = true
        } finally {
            if (!completed) withContext(NonCancellable) {
                runCatchingPreservingCancellation { documents.discard(document) }
            }
        }
    }

    override fun onCleared() {
        selectedBytes = null
    }
}

private fun ProfileRestoreResult.message(): String {
    val restored = if (profilesAdded == 0 && collectionsAdded == 0) "Backup restored into your existing Codices."
    else "Backup restored: ${countLabel(profilesAdded, "profile")} and ${countLabel(collectionsAdded, "Codex", "Codices")} added."
    val kept = keptExistingSites.mapNotNull { id -> SourceKey.parse(id)?.displayName() }
    return if (kept.isEmpty()) restored else "$restored Kept this device's settings for ${kept.joinToString()}."
}

/** "Imported 212 manga. Added 2 extension repos.", with why any of the backup's repos could not be added. */
private fun SeriesImportResult.message(): String = listOfNotNull(
    "Imported ${imported.summary()}.",
    "Added ${countLabel(reposAdded, "extension repo")}.".takeIf { reposAdded > 0 },
    "Could not add ${countLabel(repoProblems.size, "extension repo")}: ${repoProblems.values.distinct().joinToString()}.".takeIf { repoProblems.isNotEmpty() },
).joinToString(" ")

/** "212 manga · 1 anime", leaving out a kind the backup does not follow; "series progress" when it follows none. */
internal fun SeriesImportPreview.summary(): String =
    listOfNotNull(countLabel(manga, "manga", "manga").takeIf { manga > 0 }, countLabel(anime, "anime", "anime").takeIf { anime > 0 })
        .joinToString(" · ").ifEmpty { "series progress" }

/** "1 profile", "2 profiles", "1,204 chapters": [count] with [noun] or [plural]; used by the restore previews and their messages. */
internal fun countLabel(count: Int, noun: String, plural: String = "${noun}s") = "${NumberFormat.getIntegerInstance().format(count)} ${if (count == 1) noun else plural}"

private fun Throwable.backupMessage(fallback: String): String =
    when (this) {
        is ProfileRestorePendingException, is IllegalArgumentException -> message ?: fallback
        else -> fallback
    }
