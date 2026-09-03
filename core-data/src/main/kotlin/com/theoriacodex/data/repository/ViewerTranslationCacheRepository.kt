package com.theoriacodex.data.repository

data class ViewerTranslationCacheKey(
    val backendVersion: String,
    val sourceLanguage: ViewerOcrLanguage,
    val sourceText: String,
) {
    init {
        require(backendVersion.isNotBlank()) { "Translation backend version must not be blank" }
        require(sourceText.isNotBlank()) { "Translation source text must not be blank" }
    }
}

interface ViewerTranslationCacheRepository {
    suspend fun getAll(keys: Set<ViewerTranslationCacheKey>): Map<ViewerTranslationCacheKey, String>

    suspend fun putAll(translations: Map<ViewerTranslationCacheKey, String>)
}

const val DEFAULT_VIEWER_TRANSLATION_CACHE_ENTRY_LIMIT = 1_000
const val DEFAULT_VIEWER_TRANSLATION_CACHE_TTL_MS = 90L * 24L * 60L * 60L * 1_000L
