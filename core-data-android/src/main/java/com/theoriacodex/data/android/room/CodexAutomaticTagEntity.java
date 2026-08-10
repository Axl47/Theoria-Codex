package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
        tableName = "codex_automatic_tags",
        primaryKeys = { "codex_id", "source", "tag_key" },
        foreignKeys = @ForeignKey(
                entity = CodexEntity.class,
                parentColumns = "codex_id",
                childColumns = "codex_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index(value = "codex_id")
)
public final class CodexAutomaticTagEntity {
    @NonNull
    @ColumnInfo(name = "codex_id")
    private final String codexId;
    @NonNull
    private final String source;
    @NonNull
    @ColumnInfo(name = "tag_key")
    private final String tagKey;
    @NonNull
    @ColumnInfo(name = "tag_display")
    private final String tagDisplay;

    public CodexAutomaticTagEntity(
            @NonNull String codexId,
            @NonNull String source,
            @NonNull String tagKey,
            @NonNull String tagDisplay
    ) {
        this.codexId = codexId;
        this.source = source;
        this.tagKey = tagKey;
        this.tagDisplay = tagDisplay;
    }

    @NonNull public String getCodexId() { return codexId; }
    @NonNull public String getSource() { return source; }
    @NonNull public String getTagKey() { return tagKey; }
    @NonNull public String getTagDisplay() { return tagDisplay; }
}
