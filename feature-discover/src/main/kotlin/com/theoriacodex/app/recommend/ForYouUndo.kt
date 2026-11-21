package com.theoriacodex.app.recommend

import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.ForYouBlacklistEntry

/** Where For You draws its offers, such as Undo after dismissing a shelf. */
val ForYouUndoAnchor = UndoAnchor("for-you")

/** Where a shelf's why panel draws Undo after a tag is struck; the panel expires it when it closes. */
val ForYouWhyUndoAnchor = UndoAnchor("for-you-why")

/** "Hid fog + sea", naming every hidden tag set, for the Undo offer after a hide. */
fun hiddenLabel(entries: List<ForYouBlacklistEntry>): String =
    "Hid " + entries.joinToString(", ") { entry -> entry.tags.joinToString(" + ") }
