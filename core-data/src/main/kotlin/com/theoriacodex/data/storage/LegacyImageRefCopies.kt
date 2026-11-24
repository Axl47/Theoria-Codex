package com.theoriacodex.data.storage

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey

fun ImageRef.withLegacyEquivalentCopies(source: SourceKey): ImageRef =
    if (source == SourceKey.NHENTAI || source == SourceKey.HITOMI) {
        copy(progressiveUrls = emptyList(), fallbackUrls = (fallbackUrls + progressiveUrls).distinct())
    } else {
        this
    }
