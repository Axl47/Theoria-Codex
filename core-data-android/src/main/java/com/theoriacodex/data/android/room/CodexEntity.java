package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "codices")
public final class CodexEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "codex_id")
    private final String codexId;
    @NonNull
    private final String name;
    @ColumnInfo(name = "created_at_epoch_ms")
    private final long createdAtEpochMs;
    @ColumnInfo(name = "display_order")
    private final int displayOrder;

    public CodexEntity(
            @NonNull String codexId,
            @NonNull String name,
            long createdAtEpochMs,
            int displayOrder
    ) {
        this.codexId = codexId;
        this.name = name;
        this.createdAtEpochMs = createdAtEpochMs;
        this.displayOrder = displayOrder;
    }

    @NonNull public String getCodexId() { return codexId; }
    @NonNull public String getName() { return name; }
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public int getDisplayOrder() { return displayOrder; }
}
