package com.theoriacodex.app.tags

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText

/** The tappable chip for [name]; the same name can also appear as plain text in the query tray. */
internal fun SemanticsNodeInteractionsProvider.tagChip(name: String): SemanticsNodeInteraction =
    onNode(hasText(name) and hasClickAction())

/** The scrolling tag list, told apart from the query tray that also scrolls. */
internal fun SemanticsNodeInteractionsProvider.tagList(): SemanticsNodeInteraction = onNode(hasScrollToIndexAction())

internal fun SemanticsNodeInteraction.performCustomAction(label: String) {
    val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
    action.action()
}
