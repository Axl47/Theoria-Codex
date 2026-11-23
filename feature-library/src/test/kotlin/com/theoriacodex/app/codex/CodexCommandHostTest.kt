package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.undo.ProvideUndoSlot
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.InMemoryCodexLikesTransactions
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
@Config(application = Application::class)
class CodexCommandHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Undo goes to the slot where the command was given and a pending deletion closes its screen`() = runBlocking {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("saved", "Saved")
        repository.ensureCodex("deleted", "Delete me")
        val post = testPost()
        repository.addItem("saved", post)
        val original = repository.observeCodexItems("saved").first()
        val owner = CodexCommandViewModel(repository, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(repository)))
        val owners = ViewModelStore().apply { put("commands", owner) }
        val snackbar = SnackbarHostState()
        val undo = UndoSlot()
        val deleted = mutableListOf<String>()
        try {
            // No SnackbarHost timer: passive feedback remains pending until a newer notice replaces it.
            compose.setContent { ProvideUndoSlot(undo) { CodexCommandHost(owner, snackbar, deleted::add) } }
            compose.runOnIdle { owner.rename("saved", "Renamed") }
            compose.waitUntilSettled(5_000) { owner.feedback.value.isEmpty() && runBlocking { repository.observeCodex("saved").first() }?.name == "Renamed" }
            // The new name shows where it was typed, so a successful rename gives no notice.
            assertEquals(null, snackbar.currentSnackbarData)
            compose.runOnIdle { owner.remove("saved", original, listOf(post)) }
            compose.waitUntilSettled(5_000) { undo.offer.value != null }
            val removal = requireNotNull(undo.offer.value)
            assertEquals("1 post removed", removal.label)
            assertEquals(CodexUndoAnchors.detail("saved"), removal.anchor)
            assertTrue(repository.observeCodexItems("saved").first().isEmpty())
            compose.runOnIdle { undo.act(removal) }
            compose.waitUntilSettled(5_000) { snackbar.currentSnackbarData?.visuals?.message == "Posts restored" }
            assertEquals(original, repository.observeCodexItems("saved").first())

            compose.runOnIdle { owner.delete("deleted", "profile", name = "Delete me") }
            compose.waitUntilSettled(5_000) { "deleted" in deleted }
            assertEquals("Deleted Delete me", owner.pendingDeletes.value["deleted"]?.label)
            assertEquals("Delete me", repository.observeCodex("deleted").first()?.name)
        } finally {
            owners.clear()
        }
    }
}
