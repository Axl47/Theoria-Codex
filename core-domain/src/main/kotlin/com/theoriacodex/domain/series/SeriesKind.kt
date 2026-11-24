package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.SourceKey

enum class SeriesKind {
    MANGA,

    ANIME,
}

const val MIHON_SOURCE_ID_PREFIX = "custom_mihon_"

const val ANIYOMI_SOURCE_ID_PREFIX = "custom_aniyomi_"

fun SeriesKind.extensionSourceKey(id: Long): SourceKey = SourceKey.parseOrThrow(
    when (this) {
        SeriesKind.MANGA -> MIHON_SOURCE_ID_PREFIX
        SeriesKind.ANIME -> ANIYOMI_SOURCE_ID_PREFIX
    } + id,
)

val SourceKey.seriesKind: SeriesKind
    get() = if (isNumbered(ANIYOMI_SOURCE_ID_PREFIX)) SeriesKind.ANIME else SeriesKind.MANGA

fun SourceKey.isExtensionSource(): Boolean = isNumbered(MIHON_SOURCE_ID_PREFIX) || isNumbered(ANIYOMI_SOURCE_ID_PREFIX)

private fun SourceKey.isNumbered(prefix: String): Boolean =
    id.length > prefix.length && id.startsWith(prefix) && id.drop(prefix.length).all { it in '0'..'9' }
