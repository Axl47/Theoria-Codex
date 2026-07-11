package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "codex_items",
        primaryKeys = {"codex_id", "source", "source_post_id"},
        foreignKeys = {
                @ForeignKey(
                        entity = CodexEntity.class,
                        parentColumns = "codex_id",
                        childColumns = "codex_id",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = PostEntity.class,
                        parentColumns = {"source", "source_post_id"},
                        childColumns = {"source", "source_post_id"}
                )
        },
        indices = {
                @Index(value = "codex_id"),
                @Index(value = {"source", "source_post_id"})
        }
)
public final class CodexItemEntity {
    @NonNull
    @ColumnInfo(name = "codex_id")
    private final String codexId;
    @NonNull
    private final String source;
    @NonNull
    @ColumnInfo(name = "source_post_id")
    private final String sourcePostId;
    @ColumnInfo(name = "saved_at_epoch_ms")
    private final long savedAtEpochMs;

    public CodexItemEntity(
            @NonNull String codexId,
            @NonNull String source,
            @NonNull String sourcePostId,
            long savedAtEpochMs
    ) {
        this.codexId = codexId;
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.savedAtEpochMs = savedAtEpochMs;
    }

    @NonNull public String getCodexId() { return codexId; }
    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    public long getSavedAtEpochMs() { return savedAtEpochMs; }
}
