package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ViewerTranslationCacheDao {
    @Query("SELECT * FROM viewer_translation_cache WHERE backend_version = :backendVersion "
            + "AND source_language = :sourceLanguage "
            + "AND source_text_sha256 = :sourceTextSha256 LIMIT 1")
    ViewerTranslationCacheEntity find(
            String backendVersion,
            String sourceLanguage,
            String sourceTextSha256
    );

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<ViewerTranslationCacheEntity> entities);

    @Query("UPDATE viewer_translation_cache SET last_used_at_epoch_ms = :lastUsedAtEpochMs "
            + "WHERE backend_version = :backendVersion AND source_language = :sourceLanguage "
            + "AND source_text_sha256 = :sourceTextSha256")
    int touch(
            String backendVersion,
            String sourceLanguage,
            String sourceTextSha256,
            long lastUsedAtEpochMs
    );

    @Query("DELETE FROM viewer_translation_cache WHERE backend_version = :backendVersion "
            + "AND source_language = :sourceLanguage AND source_text_sha256 = :sourceTextSha256")
    int delete(String backendVersion, String sourceLanguage, String sourceTextSha256);

    @Query("DELETE FROM viewer_translation_cache WHERE "
            + "(backend_version, source_language, source_text_sha256) IN "
            + "(SELECT backend_version, source_language, source_text_sha256 "
            + "FROM viewer_translation_cache ORDER BY last_used_at_epoch_ms DESC, "
            + "backend_version ASC, source_language ASC, source_text_sha256 ASC "
            + "LIMIT -1 OFFSET :limit)")
    int trimToLimit(int limit);

    @Query("SELECT COUNT(*) FROM viewer_translation_cache")
    int count();
}
