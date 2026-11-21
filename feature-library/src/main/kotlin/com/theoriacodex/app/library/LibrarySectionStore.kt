package com.theoriacodex.app.library

import com.theoriacodex.data.repository.UiRestoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Library tab's selected section. App-wide, so a jump from anywhere can pick a section before
 * Library shows; every choice is persisted in the UI-restore store and restored at startup.
 */
class LibrarySectionStore(
    private val store: UiRestoreRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val selected = MutableStateFlow(LibrarySection.Codices)
    val section: StateFlow<LibrarySection> = selected.asStateFlow()

    fun select(section: LibrarySection) {
        if (selected.value == section) return
        selected.value = section
        // Writes the latest choice rather than this one, so writes finishing out of order still end on it.
        scope.launch { store.setLibrarySection(selected.value.name) }
    }

    /** Applies [legacy] (from a tab that became a section) or else the stored section. */
    suspend fun restore(legacy: LibrarySection?) {
        if (legacy != null) select(legacy)
        else LibrarySection.named(store.getLibrarySection())?.let { selected.value = it }
    }
}
