package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/**
 * What the app derived from a stored post's picture. Rows follow their post: deleting the post deletes them. A null
 * [phash] records that the picture was looked at and could not be hashed (too flat, not an image), so it is not
 * fetched again.
 */
@Entity(
        tableName = "post_visual_signatures",
        primaryKeys = {"source", "source_post_id"},
        foreignKeys = @ForeignKey(
                entity = PostEntity.class,
                parentColumns = {"source", "source_post_id"},
                childColumns = {"source", "source_post_id"},
                onDelete = ForeignKey.CASCADE
        )
)
public final class VisualSignatureEntity {
    @NonNull private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @Nullable private final Long phash;
    @ColumnInfo(name = "indexed_at_epoch_ms") private final long indexedAtEpochMs;

    public VisualSignatureEntity(
            @NonNull String source,
            @NonNull String sourcePostId,
            @Nullable Long phash,
            long indexedAtEpochMs
    ) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.phash = phash;
        this.indexedAtEpochMs = indexedAtEpochMs;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @Nullable public Long getPhash() { return phash; }
    public long getIndexedAtEpochMs() { return indexedAtEpochMs; }
}
