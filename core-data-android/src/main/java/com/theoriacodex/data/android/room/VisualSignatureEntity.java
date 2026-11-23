package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/**
 * What the app derived from a stored post's picture. Rows follow their post: deleting the post deletes them.
 * <p>
 * A null [phash] with [phashChecked] records that the picture was looked at and could not be hashed (too flat, not an
 * image), so it is not fetched again; without [phashChecked] the row was made by the tagger and no hash was tried yet.
 * [modelId] names the tagger model that analysed the picture: with a null [embedding] and null [predictedTags] it
 * looked and could not analyse it. [embedding] is opaque bytes; [predictedTags] is the text
 * {@code PredictedTagCodec} writes.
 * <p>
 * [frameState] says whether that analysis read only the still (0), only the still of a post that plays, whose frames
 * are still to be read (1), or the still and frames from across the post (2). [frameAttempts] counts failed frame reads;
 * at the cap the still analysis stands for good.
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
    @ColumnInfo(name = "phash_checked", defaultValue = "1") private final boolean phashChecked;
    @ColumnInfo(name = "indexed_at_epoch_ms") private final long indexedAtEpochMs;
    @Nullable @ColumnInfo(name = "model_id") private final String modelId;
    @Nullable private final byte[] embedding;
    @Nullable @ColumnInfo(name = "predicted_tags") private final String predictedTags;
    @ColumnInfo(name = "frame_state", defaultValue = "0") private final int frameState;
    @ColumnInfo(name = "frame_attempts", defaultValue = "0") private final int frameAttempts;

    public VisualSignatureEntity(
            @NonNull String source,
            @NonNull String sourcePostId,
            @Nullable Long phash,
            boolean phashChecked,
            long indexedAtEpochMs,
            @Nullable String modelId,
            @Nullable byte[] embedding,
            @Nullable String predictedTags,
            int frameState,
            int frameAttempts
    ) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.phash = phash;
        this.phashChecked = phashChecked;
        this.indexedAtEpochMs = indexedAtEpochMs;
        this.modelId = modelId;
        this.embedding = embedding;
        this.predictedTags = predictedTags;
        this.frameState = frameState;
        this.frameAttempts = frameAttempts;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @Nullable public Long getPhash() { return phash; }
    public boolean getPhashChecked() { return phashChecked; }
    public long getIndexedAtEpochMs() { return indexedAtEpochMs; }
    @Nullable public String getModelId() { return modelId; }
    @Nullable public byte[] getEmbedding() { return embedding; }
    @Nullable public String getPredictedTags() { return predictedTags; }
    public int getFrameState() { return frameState; }
    public int getFrameAttempts() { return frameAttempts; }
}
