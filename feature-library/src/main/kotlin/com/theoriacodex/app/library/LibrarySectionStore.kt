package com.theoriacodex.app.library

import com.theoriacodex.data.repository.UiRestoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibrarySectionStore(
    private val store: UiRestoreRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val selected = MutableStateFlow(LibrarySection.Codices)
    val section: StateFlow<LibrarySection> = selected.asStateFlow()

    fun select(section: LibrarySection) {
        if (selected.value == section) return
        selected.value = section
        scope.launch { store.setLibrarySection(selected.value.name) }
    }

    suspend fun restore(legacy: LibrarySection?) {
        if (legacy != null) select(legacy)
        else LibrarySection.named(store.getLibrarySection())?.let { selected.value = it }
    }
}
