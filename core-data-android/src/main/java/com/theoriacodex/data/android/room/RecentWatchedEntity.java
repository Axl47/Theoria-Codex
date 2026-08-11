package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "recent_watched",
        primaryKeys = {"source", "source_post_id", "section"},
        foreignKeys = @ForeignKey(
                entity = PostEntity.class,
                parentColumns = {"source", "source_post_id"},
                childColumns = {"source", "source_post_id"},
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index(value = {"viewed_at_epoch_ms", "sort_sequence", "source", "source_post_id", "section"})
)
public final class RecentWatchedEntity {
    @NonNull private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @NonNull private final String section;
    @ColumnInfo(name = "viewed_at_epoch_ms") private final long viewedAtEpochMs;
    @ColumnInfo(name = "sort_sequence") private final long sortSequence;
    @NonNull private final String origin;
    @Nullable @ColumnInfo(name = "origin_query_hash") private final String originQueryHash;
    @ColumnInfo(name = "max_viewed_media_number", defaultValue = "1")
    private final int maxViewedMediaNumber;

    public RecentWatchedEntity(@NonNull String source, @NonNull String sourcePostId,
            @NonNull String section,
            long viewedAtEpochMs, long sortSequence, @NonNull String origin,
            @Nullable String originQueryHash, int maxViewedMediaNumber) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.section = section;
        this.viewedAtEpochMs = viewedAtEpochMs;
        this.sortSequence = sortSequence;
        this.origin = origin;
        this.originQueryHash = originQueryHash;
        this.maxViewedMediaNumber = maxViewedMediaNumber;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @NonNull public String getSection() { return section; }
    public long getViewedAtEpochMs() { return viewedAtEpochMs; }
    public long getSortSequence() { return sortSequence; }
    @NonNull public String getOrigin() { return origin; }
    @Nullable public String getOriginQueryHash() { return originQueryHash; }
    public int getMaxViewedMediaNumber() { return maxViewedMediaNumber; }
}
