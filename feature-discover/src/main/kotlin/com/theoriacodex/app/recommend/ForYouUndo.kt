package com.theoriacodex.app.recommend

import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.ForYouBlacklistEntry

/** Where For You draws its offers, such as Undo after dismissing a shelf. */
val ForYouUndoAnchor = UndoAnchor("for-you")

/**
 * Where Undo for a tag struck in a why panel draws: in the panel while it is open, and on For You once it
 * closes, so a hide that lands after the panel closed can still be taken back.
 */
val ForYouWhyUndoAnchor = UndoAnchor("for-you-why")

/** "Hid fog + sea", naming every hidden tag set, for the Undo offer after a hide. */
fun hiddenLabel(entries: List<ForYouBlacklistEntry>): String =
    "Hid " + entries.joinToString(", ") { entry -> entry.tags.joinToString(" + ") }
