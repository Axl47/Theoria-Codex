package com.theoriacodex.app.tags

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText

fun SemanticsNodeInteractionsProvider.tagChip(name: String): SemanticsNodeInteraction =
    onNode(hasText(name) and hasClickAction())

fun SemanticsNodeInteractionsProvider.tagList(): SemanticsNodeInteraction = onNode(hasScrollToIndexAction())

internal fun SemanticsNodeInteraction.performCustomAction(label: String) {
    val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
    action.action()
}
