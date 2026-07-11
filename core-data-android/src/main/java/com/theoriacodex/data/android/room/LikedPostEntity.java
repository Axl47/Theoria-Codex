package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "liked_posts",
        primaryKeys = {"profile_id", "source", "source_post_id"},
        indices = @Index(value = {"profile_id", "liked_at_epoch_ms"})
)
public final class LikedPostEntity {
    @NonNull
    @ColumnInfo(name = "profile_id")
    private final String profileId;
    @NonNull
    private final String source;
    @NonNull
    @ColumnInfo(name = "source_post_id")
    private final String sourcePostId;
    @ColumnInfo(name = "liked_at_epoch_ms")
    private final long likedAtEpochMs;
    @NonNull
    @ColumnInfo(name = "tags_json")
    private final String tagsJson;

    public LikedPostEntity(
            @NonNull String profileId,
            @NonNull String source,
            @NonNull String sourcePostId,
            long likedAtEpochMs,
            @NonNull String tagsJson
    ) {
        this.profileId = profileId;
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.likedAtEpochMs = likedAtEpochMs;
        this.tagsJson = tagsJson;
    }

    @NonNull public String getProfileId() { return profileId; }
    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    public long getLikedAtEpochMs() { return likedAtEpochMs; }
    @NonNull public String getTagsJson() { return tagsJson; }
}
