package com.theoriacodex.app.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.ProfileBackupPreview
import com.theoriacodex.data.repository.ProfileBackupService
import com.theoriacodex.data.repository.ProfileRestoreResult
import com.theoriacodex.data.repository.ProfileRestorePendingException
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
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

internal interface BackupActions {
    suspend fun export(includeRecents: Boolean): ByteArray
    fun preview(bytes: ByteArray): ProfileBackupPreview
    suspend fun restore(bytes: ByteArray, applyPreferences: Boolean): ProfileRestoreResult
}

internal class ProfileBackupActions(private val service: ProfileBackupService) : BackupActions {
    override suspend fun export(includeRecents: Boolean) = service.export(includeRecents)
    override fun preview(bytes: ByteArray) = service.preview(bytes)
    override suspend fun restore(bytes: ByteArray, applyPreferences: Boolean) = service.restore(bytes, applyPreferences)
}

internal enum class BackupOperation { EXPORT, READ, RESTORE }

internal data class BackupUiState(
    val operation: BackupOperation? = null,
    val showExportOptions: Boolean = false,
    val includeRecents: Boolean = true,
    val preview: ProfileBackupPreview? = null,
    val applyPreferences: Boolean = false,
) {
    val busy: Boolean get() = operation != null
}

/** Activity-owned jobs and selected bytes survive UI recreation without reopening the document. */
internal class BackupViewModel(
    private val actions: BackupActions,
    private val documents: BackupDocuments,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()
    private var selectedBytes: ByteArray? = null

    fun showExportOptions() {
        if (!state.value.busy) mutableState.update { it.copy(showExportOptions = true) }
    }

    fun includeRecents(include: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(includeRecents = include) }
    }

    fun applyPreferences(apply: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(applyPreferences = apply) }
    }

    fun dismissDialog() {
        if (state.value.busy) return
        selectedBytes = null
        mutableState.update { it.copy(showExportOptions = false, preview = null, applyPreferences = false) }
    }

    fun export(document: String?) {
        if (document == null || state.value.busy) return
        val includeRecents = state.value.includeRecents
        mutableState.update { it.copy(operation = BackupOperation.EXPORT, showExportOptions = false) }
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
            it.copy(operation = BackupOperation.READ, showExportOptions = false, preview = null, applyPreferences = false)
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
        if (state.value.busy || state.value.preview == null) return
        val applyPreferences = state.value.applyPreferences
        mutableState.update { it.copy(operation = BackupOperation.RESTORE) }
        viewModelScope.launch {
            val result = runCatchingPreservingCancellation {
                withContext(ioDispatcher) { actions.restore(bytes, applyPreferences) }
            }
            mutableState.update { it.copy(operation = null) }
            result.fold(
                onSuccess = { restored ->
                    dismissDialog()
                    messages.send("Backup restored: ${restored.profilesAdded} profiles and ${restored.collectionsAdded} collections added.")
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

private fun Throwable.backupMessage(fallback: String): String =
    when (this) {
        is ProfileRestorePendingException, is IllegalArgumentException -> message ?: fallback
        else -> fallback
    }
