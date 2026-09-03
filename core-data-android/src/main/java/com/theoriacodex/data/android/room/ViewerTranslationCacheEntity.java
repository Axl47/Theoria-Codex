package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "viewer_translation_cache",
        primaryKeys = {"backend_version", "source_language", "source_text_sha256"},
        indices = {
                @Index(value = {
                        "last_used_at_epoch_ms", "backend_version", "source_language",
                        "source_text_sha256"
                })
        }
)
public final class ViewerTranslationCacheEntity {
    @NonNull @ColumnInfo(name = "backend_version") private final String backendVersion;
    @NonNull @ColumnInfo(name = "source_language") private final String sourceLanguage;
    @NonNull @ColumnInfo(name = "source_text_sha256") private final String sourceTextSha256;
    @NonNull @ColumnInfo(name = "translated_text") private final String translatedText;
    @ColumnInfo(name = "expires_at_epoch_ms") private final long expiresAtEpochMs;
    @ColumnInfo(name = "last_used_at_epoch_ms") private final long lastUsedAtEpochMs;

    public ViewerTranslationCacheEntity(
            @NonNull String backendVersion,
            @NonNull String sourceLanguage,
            @NonNull String sourceTextSha256,
            @NonNull String translatedText,
            long expiresAtEpochMs,
            long lastUsedAtEpochMs
    ) {
        this.backendVersion = backendVersion;
        this.sourceLanguage = sourceLanguage;
        this.sourceTextSha256 = sourceTextSha256;
        this.translatedText = translatedText;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.lastUsedAtEpochMs = lastUsedAtEpochMs;
    }

    @NonNull public String getBackendVersion() { return backendVersion; }
    @NonNull public String getSourceLanguage() { return sourceLanguage; }
    @NonNull public String getSourceTextSha256() { return sourceTextSha256; }
    @NonNull public String getTranslatedText() { return translatedText; }
    public long getExpiresAtEpochMs() { return expiresAtEpochMs; }
    public long getLastUsedAtEpochMs() { return lastUsedAtEpochMs; }
}
