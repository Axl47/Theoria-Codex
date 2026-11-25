package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.domain.model.SourceKey

@Composable
fun rememberSavedTagAction(source: SourceKey, tag: String): TagChipAction? {
    val savedTags = LocalPostTagSavedTags.current
    val saved by savedTags.saved.collectAsStateWithLifecycle(initialValue = emptySet())
    val haptics = LocalHapticFeedback.current
    val add = savedTags.add ?: return null
    val isSaved = (source to tag) in saved
    val toggle = savedTagToggle(haptics, isSaved, add, savedTags.remove, source, tag)
    return TagChipAction(if (isSaved) "Remove from saved tags" else "Save", toggle)
}

internal fun savedTagToggle(
    haptics: HapticFeedback,
    saved: Boolean,
    add: (SourceKey, String) -> Unit,
    remove: (SourceKey, String) -> Unit,
    source: SourceKey,
    tag: String,
): () -> Unit = {
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    if (saved) remove(source, tag) else add(source, tag)
}
