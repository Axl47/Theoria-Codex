package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "for_you_verdicts",
        primaryKeys = {"profile_id", "source", "source_post_id"},
        indices = @Index(value = {"profile_id", "decided_at_epoch_ms"})
)
public final class ForYouVerdictEntity {
    @NonNull @ColumnInfo(name = "profile_id") private final String profileId;
    @NonNull private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @NonNull private final String decision;
    @ColumnInfo(name = "decided_at_epoch_ms") private final long decidedAtEpochMs;
    @Nullable @ColumnInfo(name = "preview_url") private final String previewUrl;

    public ForYouVerdictEntity(
            @NonNull String profileId,
            @NonNull String source,
            @NonNull String sourcePostId,
            @NonNull String decision,
            long decidedAtEpochMs,
            @Nullable String previewUrl
    ) {
        this.profileId = profileId;
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.decision = decision;
        this.decidedAtEpochMs = decidedAtEpochMs;
        this.previewUrl = previewUrl;
    }

    @NonNull public String getProfileId() { return profileId; }
    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @NonNull public String getDecision() { return decision; }
    public long getDecidedAtEpochMs() { return decidedAtEpochMs; }
    @Nullable public String getPreviewUrl() { return previewUrl; }
}
