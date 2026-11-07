package com.theoriacodex.app.source

import androidx.compose.runtime.saveable.Saver
import com.theoriacodex.domain.model.SourceKey

/**
 * Saves an optional source as its persisted id. `SourceKey` is a value class, so unlike the old
 * enum it is not `Serializable` and cannot go into a saved-state Bundle directly.
 */
internal val NullableSourceKeySaver = Saver<SourceKey?, String>(
    save = { source -> source?.id },
    restore = SourceKey::parse,
)
