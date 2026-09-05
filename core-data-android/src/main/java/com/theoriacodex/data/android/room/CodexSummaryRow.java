package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

/** One bounded cover row; empty collections retain a row with null Post columns. */
public final class CodexSummaryRow {
    @NonNull @ColumnInfo(name = "codex_id") public final String codexId;
    @ColumnInfo(name = "item_count") public final int itemCount;
    @Nullable public final String source;
    @Nullable @ColumnInfo(name = "source_post_id") public final String sourcePostId;
    @Nullable @ColumnInfo(name = "payload_json") public final String payloadJson;

    public CodexSummaryRow(@NonNull String codexId, int itemCount, @Nullable String source,
            @Nullable String sourcePostId, @Nullable String payloadJson) {
        this.codexId = codexId;
        this.itemCount = itemCount;
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.payloadJson = payloadJson;
    }
}
