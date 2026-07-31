package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "recent_watched",
        primaryKeys = {"source", "source_post_id"},
        foreignKeys = @ForeignKey(
                entity = PostEntity.class,
                parentColumns = {"source", "source_post_id"},
                childColumns = {"source", "source_post_id"},
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index(value = {"viewed_at_epoch_ms", "sort_sequence", "source", "source_post_id"})
)
public final class RecentWatchedEntity {
    @NonNull private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @ColumnInfo(name = "viewed_at_epoch_ms") private final long viewedAtEpochMs;
    @ColumnInfo(name = "sort_sequence") private final long sortSequence;
    @NonNull private final String origin;
    @Nullable @ColumnInfo(name = "origin_query_hash") private final String originQueryHash;

    public RecentWatchedEntity(@NonNull String source, @NonNull String sourcePostId,
            long viewedAtEpochMs, long sortSequence, @NonNull String origin,
            @Nullable String originQueryHash) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.viewedAtEpochMs = viewedAtEpochMs;
        this.sortSequence = sortSequence;
        this.origin = origin;
        this.originQueryHash = originQueryHash;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    public long getViewedAtEpochMs() { return viewedAtEpochMs; }
    public long getSortSequence() { return sortSequence; }
    @NonNull public String getOrigin() { return origin; }
    @Nullable public String getOriginQueryHash() { return originQueryHash; }
}
