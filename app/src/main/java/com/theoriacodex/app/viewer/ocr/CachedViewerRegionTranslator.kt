package com.theoriacodex.app.viewer.ocr

import com.theoriacodex.app.viewer.ViewerOcrRegion
import com.theoriacodex.data.repository.ViewerTranslationCacheKey
import com.theoriacodex.data.repository.ViewerTranslationCacheRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

internal interface ViewerRegionTranslator {
    suspend fun translate(
        regions: List<ViewerOcrRegion>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String>
}

/** Resolves a current image from the durable local cache before purchasing missing translations. */
internal class CachedViewerRegionTranslator(
    private val remote: ViewerTextTranslator,
    private val cache: ViewerTranslationCacheRepository,
    private val recordTranslationUsage: suspend (phraseCount: Long, sourceCharacterCount: Long) -> Unit = { _, _ -> },
) : ViewerRegionTranslator {
    override suspend fun translate(
        regions: List<ViewerOcrRegion>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String> {
        if (regions.isEmpty()) return emptyMap()
        val keysByRegion = regions.associate { region ->
            region.id to ViewerTranslationCacheKey(
                backendVersion = GOOGLE_NMT_CACHE_VERSION,
                sourceLanguage = region.language,
                sourceText = region.sourceText.trim(),
            )
        }
        val cached = cache.getAll(keysByRegion.values.toSet())
        val fetched = buildMap {
            keysByRegion.values.toSet()
                .filterNot(cached::containsKey)
                .groupBy(ViewerTranslationCacheKey::sourceLanguage)
                .forEach { (language, keys) ->
                    val translations = remote.translateBatch(
                        language = language,
                        sourceTexts = keys.map(ViewerTranslationCacheKey::sourceText),
                        onStage = onStage,
                    )
                    keys.forEach { key ->
                        translations[key.sourceText]?.let { translated -> put(key, translated) }
                    }
                }
        }
        cache.putAll(fetched)
        if (fetched.isNotEmpty()) {
            runCatchingPreservingCancellation {
                recordTranslationUsage(
                    fetched.size.toLong(),
                    fetched.keys.sumOf { key -> key.sourceText.length.toLong() },
                )
            }
        }
        val resolved = cached + fetched
        return keysByRegion.mapNotNull { (regionId, key) ->
            resolved[key]?.let { translated -> regionId to translated }
        }.toMap()
    }
}

internal const val GOOGLE_NMT_CACHE_VERSION = "google-nmt-v1"
