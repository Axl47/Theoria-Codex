package com.theoriacodex.app.viewer

import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSaveButtonModelTest {
    private val art = post("art")
    private fun suggestion(id: String) = SaveSuggestion(id, "Codex $id", emptyList())
    private fun suggestions(vararg ids: String, inAny: Boolean = false) = SaveSuggestions(ids.map(::suggestion), inAny)
    private fun record(inserted: Int = 1, at: Long = 1_000L, postId: String = "art") =
        QuickSaveRecord(post(postId), "a", "Codex a", inserted, at)

    @Test
    fun `suggests the top Codex, then confirms the save with Undo, then moves to the next suggestion`() {
        val before = viewerSaveButtonState(art.id, suggestions("a", "b"), lastSave = null, nowEpochMs = 0L)
        assertEquals(ViewerSaveButtonState.Suggest(suggestion("a"), inAnyCodex = false), before)
        assertEquals(ViewerSaveCommand.QuickSave("a"), before.tapCommand())

        val saved = viewerSaveButtonState(art.id, suggestions("a", "b"), record(), nowEpochMs = 2_000L)
        assertEquals(ViewerSaveButtonState.Saved(record()), saved)
        assertEquals(ViewerSaveCommand.Undo, saved.tapCommand())

        val later = viewerSaveButtonState(art.id, suggestions("b", inAny = true), record(), nowEpochMs = 1_000L + VIEWER_SAVED_UNDO_MS)
        assertEquals(ViewerSaveButtonState.Suggest(suggestion("b"), inAnyCodex = true), later)
    }

    @Test
    fun `a clock read just before the save landed still shows it`() {
        assertEquals(ViewerSaveButtonState.Saved(record()), viewerSaveButtonState(art.id, null, record(), nowEpochMs = 900L))
    }

    @Test
    fun `another post's save is not confirmed here`() {
        val state = viewerSaveButtonState(art.id, suggestions("a"), record(postId = "other"), nowEpochMs = 1_500L)
        assertEquals(ViewerSaveButtonState.Suggest(suggestion("a"), inAnyCodex = false), state)
    }

    @Test
    fun `a save that added nothing cannot be undone and opens the picker instead`() {
        assertEquals(ViewerSaveCommand.OpenPicker, viewerSaveButtonState(art.id, null, record(inserted = 0), 1_500L).tapCommand())
    }

    @Test
    fun `with no Codex at all a tap names a new one, and with none to suggest or none loaded it opens the picker`() {
        val noCodices = viewerSaveButtonState(art.id, SaveSuggestions.Empty, null, 0L)
        assertEquals(ViewerSaveButtonState.NoTarget(inAnyCodex = false), noCodices)
        assertEquals(ViewerSaveCommand.NewCodex, noCodices.tapCommand())
        val inEveryCodex = viewerSaveButtonState(art.id, SaveSuggestions(emptyList(), inAnyCodex = true), null, 0L)
        assertEquals(ViewerSaveCommand.OpenPicker, inEveryCodex.tapCommand())
        assertEquals(ViewerSaveCommand.OpenPicker, viewerSaveButtonState(art.id, null, null, 0L).tapCommand())
    }

    @Test
    fun `the fan holds the next three suggestions then New, and releasing on New names a new Codex`() {
        val fan = viewerSaveFanTargets(suggestions("a", "b", "c", "d", "e"))
        assertEquals(
            listOf("b", "c", "d").map { ViewerSaveFanTarget.Codex(suggestion(it)) } + ViewerSaveFanTarget.New,
            fan,
        )
        assertEquals(listOf(ViewerSaveFanTarget.New), viewerSaveFanTargets(null))
        assertEquals(ViewerSaveCommand.QuickSave("b"), viewerSaveFanRelease(fan.first()))
        assertEquals(ViewerSaveCommand.NewCodex, viewerSaveFanRelease(ViewerSaveFanTarget.New))
        assertEquals(ViewerSaveCommand.OpenPicker, viewerSaveFanRelease(null))
    }
}
