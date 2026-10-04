package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCodexRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CodexCommandHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Undo preempts passive feedback and coalescing still delivers deletion navigation`() = runBlocking {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("saved", "Saved")
        repository.ensureCodex("deleted", "Delete me")
        val post = testPost()
        repository.addItem("saved", post)
        val original = repository.observeCodexItems("saved").first()
        val owner = CodexCommandViewModel(repository, {}, {})
        val owners = ViewModelStore().apply { put("commands", owner) }
        val snackbar = SnackbarHostState()
        val deleted = mutableListOf<String>()
        try {
            // No SnackbarHost timer: passive feedback remains pending unless the command host preempts it.
            compose.setContent { CodexCommandHost(owner, snackbar, deleted::add) }
            compose.runOnIdle { owner.rename("saved", "Renamed") }
            compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == "Codex renamed" }
            compose.runOnIdle { owner.remove("saved", original, listOf(post)) }
            compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.actionLabel == "Undo" }
            assertTrue(repository.observeCodexItems("saved").first().isEmpty())
            compose.runOnIdle { requireNotNull(snackbar.currentSnackbarData).performAction() }
            compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == "Posts restored" }
            assertEquals(original, repository.observeCodexItems("saved").first())

            compose.runOnIdle {
                owner.delete("deleted", "profile")
                owner.rename("saved", "Newest")
            }
            compose.waitUntil(5_000) { deleted == listOf("deleted") }
            compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == "Codex renamed" }
            assertEquals(null, repository.observeCodex("deleted").first())
        } finally {
            owners.clear()
        }
    }
}
