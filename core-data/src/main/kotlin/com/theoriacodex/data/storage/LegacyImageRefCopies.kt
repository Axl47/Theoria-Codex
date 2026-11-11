package com.theoriacodex.data.storage

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey

/**
 * Legacy decode only. Before [ImageRef.fallbackUrls] existed, nhentai and Hitomi stored their
 * equivalent copies (mirrors, other formats, other shards) in `progressiveUrls`. Call this for a
 * stored image whose record has no fallback field so those copies stay failure fallbacks instead of
 * being read as lighter renditions. Every other source stored real renditions and is returned as is.
 */
fun ImageRef.withLegacyEquivalentCopies(source: SourceKey): ImageRef =
    if (source == SourceKey.NHENTAI || source == SourceKey.HITOMI) {
        copy(progressiveUrls = emptyList(), fallbackUrls = (fallbackUrls + progressiveUrls).distinct())
    } else {
        this
    }
