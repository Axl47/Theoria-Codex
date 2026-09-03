package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.DEFAULT_VIEWER_TRANSLATION_CACHE_ENTRY_LIMIT
import com.theoriacodex.data.repository.DEFAULT_VIEWER_TRANSLATION_CACHE_TTL_MS
import com.theoriacodex.data.repository.ViewerTranslationCacheKey
import com.theoriacodex.data.repository.ViewerTranslationCacheRepository
import java.security.MessageDigest

class RoomViewerTranslationCacheRepository(
    private val database: TheoriaRoomDatabase,
    private val maxEntries: Int = DEFAULT_VIEWER_TRANSLATION_CACHE_ENTRY_LIMIT,
    private val ttlMs: Long = DEFAULT_VIEWER_TRANSLATION_CACHE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewerTranslationCacheRepository {
    private val dao = database.viewerTranslationCacheDao()

    init {
        require(maxEntries > 0) { "Translation cache entry limit must be positive" }
        require(ttlMs > 0L) { "Translation cache TTL must be positive" }
    }

    override suspend fun getAll(
        keys: Set<ViewerTranslationCacheKey>,
    ): Map<ViewerTranslationCacheKey, String> = database.withTransaction {
        val now = clock()
        buildMap {
            keys.forEach { key ->
                val hash = key.sourceText.sha256()
                val entity = dao.find(key.backendVersion, key.sourceLanguage.name, hash)
                    ?: return@forEach
                if (entity.expiresAtEpochMs <= now) {
                    dao.delete(entity.backendVersion, entity.sourceLanguage, entity.sourceTextSha256)
                } else {
                    dao.touch(
                        entity.backendVersion,
                        entity.sourceLanguage,
                        entity.sourceTextSha256,
                        now,
                    )
                    put(key, entity.translatedText)
                }
            }
        }
    }

    override suspend fun putAll(translations: Map<ViewerTranslationCacheKey, String>) {
        if (translations.isEmpty()) return
        database.withTransaction {
            val now = clock()
            val expiresAt = now + ttlMs
            dao.upsertAll(
                translations.map { (key, translatedText) ->
                    require(translatedText.isNotBlank()) { "Cached translation must not be blank" }
                    ViewerTranslationCacheEntity(
                        key.backendVersion,
                        key.sourceLanguage.name,
                        key.sourceText.sha256(),
                        translatedText,
                        expiresAt,
                        now,
                    )
                },
            )
            dao.trimToLimit(maxEntries)
        }
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
