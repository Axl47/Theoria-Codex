package com.theoriacodex.app.source

import androidx.compose.runtime.saveable.Saver
import com.theoriacodex.domain.model.SourceKey

val NullableSourceKeySaver = Saver<SourceKey?, String>(
    save = { source -> source?.id },
    restore = SourceKey::parse,
)
