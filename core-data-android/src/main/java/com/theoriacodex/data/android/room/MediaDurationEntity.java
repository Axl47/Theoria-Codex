package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "media_durations",
        primaryKeys = {"source", "source_post_id", "media_fingerprint"},
        indices = {
                @Index(value = {
                        "updated_at_epoch_ms", "source", "source_post_id", "media_fingerprint"
                })
        }
)
public final class MediaDurationEntity {
    @NonNull @ColumnInfo(name = "source") private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @NonNull @ColumnInfo(name = "media_fingerprint") private final String mediaFingerprint;
    @NonNull @ColumnInfo(name = "decision") private final String decision;
    @Nullable @ColumnInfo(name = "duration_ms") private final Long durationMs;
    @Nullable @ColumnInfo(name = "provenance") private final String provenance;
    @Nullable @ColumnInfo(name = "reason") private final String reason;
    @Nullable @ColumnInfo(name = "retry_at_epoch_ms") private final Long retryAtEpochMs;
    @ColumnInfo(name = "updated_at_epoch_ms") private final long updatedAtEpochMs;

    public MediaDurationEntity(
            @NonNull String source,
            @NonNull String sourcePostId,
            @NonNull String mediaFingerprint,
            @NonNull String decision,
            @Nullable Long durationMs,
            @Nullable String provenance,
            @Nullable String reason,
            @Nullable Long retryAtEpochMs,
            long updatedAtEpochMs
    ) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.mediaFingerprint = mediaFingerprint;
        this.decision = decision;
        this.durationMs = durationMs;
        this.provenance = provenance;
        this.reason = reason;
        this.retryAtEpochMs = retryAtEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @NonNull public String getMediaFingerprint() { return mediaFingerprint; }
    @NonNull public String getDecision() { return decision; }
    @Nullable public Long getDurationMs() { return durationMs; }
    @Nullable public String getProvenance() { return provenance; }
    @Nullable public String getReason() { return reason; }
    @Nullable public Long getRetryAtEpochMs() { return retryAtEpochMs; }
    public long getUpdatedAtEpochMs() { return updatedAtEpochMs; }
}
