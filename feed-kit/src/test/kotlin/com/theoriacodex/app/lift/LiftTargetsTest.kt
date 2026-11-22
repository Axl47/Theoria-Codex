package com.theoriacodex.app.lift

import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.ui.navigation.LiftLike
import org.junit.Assert.assertEquals
import org.junit.Test

class LiftTargetsTest {
    private val suggestions = SaveSuggestions(
        targets = (1..4).map { SaveSuggestion("codex-$it", "Codex $it", emptyList()) },
        inAnyCodex = false,
    )

    @Test
    fun `the end edge offers the top three suggestions then New and More`() {
        val targets = liftTargets(suggestions, like = null, similarAvailable = false)

        assertEquals(listOf("Codex 1", "Codex 2", "Codex 3", "New", "More"), targets.save.map { it.label })
        assertEquals(LiftAction.Save("codex-1", "Codex 1"), targets.save.first().payload)
        assertEquals(listOf(LiftAction.NewCodex, LiftAction.OpenPicker), targets.save.takeLast(2).map { it.payload })
    }

    @Test
    fun `with no suggestions only New and More are offered`() {
        assertEquals(
            listOf(LiftAction.NewCodex, LiftAction.OpenPicker),
            liftTargets(SaveSuggestions.Empty, null, false).save.map { it.payload },
        )
    }

    @Test
    fun `the start edge offers Like or Unlike by the card's state, then Similar where available`() {
        assertEquals(listOf("Like", "Similar"), liftTargets(suggestions, LiftLike(liked = false) {}, true).quick.map { it.label })
        assertEquals(listOf("Unlike"), liftTargets(suggestions, LiftLike(liked = true) {}, false).quick.map { it.label })
        assertEquals(emptyList<String>(), liftTargets(suggestions, like = null, similarAvailable = false).quick.map { it.label })
    }
}
