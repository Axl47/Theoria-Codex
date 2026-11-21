package com.theoriacodex.app.library

import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionStoreTest {
    @Test fun `a chosen section survives a restart`() = runTest {
        val disk = InMemoryUiRestoreRepository()
        LibrarySectionStore(disk, this).select(LibrarySection.History)
        advanceUntilIdle()

        val restarted = LibrarySectionStore(disk, this)
        restarted.restore(legacy = null)
        assertEquals(LibrarySection.History, restarted.section.value)
    }

    @Test fun `a retired tab's section replaces the stored one and is persisted`() = runTest {
        val disk = InMemoryUiRestoreRepository().apply { setLibrarySection(LibrarySection.Saved.name) }
        val store = LibrarySectionStore(disk, this)
        store.restore(legacy = LibrarySection.History)
        advanceUntilIdle()
        assertEquals(LibrarySection.History, store.section.value)
        assertEquals(LibrarySection.History.name, disk.getLibrarySection())
    }

    @Test fun `rapid choices persist the last one`() = runTest {
        val disk = InMemoryUiRestoreRepository()
        val store = LibrarySectionStore(disk, this)
        store.select(LibrarySection.Saved)
        store.select(LibrarySection.History)
        advanceUntilIdle()
        assertEquals(LibrarySection.History.name, disk.getLibrarySection())
    }

    @Test fun `unknown stored names keep the default`() = runTest {
        val disk = InMemoryUiRestoreRepository().apply { setLibrarySection("Recents") }
        val store = LibrarySectionStore(disk, this)
        store.restore(legacy = null)
        assertEquals(LibrarySection.Codices, store.section.value)
    }
}
