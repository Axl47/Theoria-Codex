package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;

public final class CodexPostRow {
    @NonNull private final String source;
    @NonNull @ColumnInfo(name = "source_post_id") private final String sourcePostId;
    @NonNull @ColumnInfo(name = "payload_json") private final String payloadJson;
    @ColumnInfo(name = "saved_at_epoch_ms") private final long savedAtEpochMs;

    public CodexPostRow(
            @NonNull String source,
            @NonNull String sourcePostId,
            @NonNull String payloadJson,
            long savedAtEpochMs
    ) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.payloadJson = payloadJson;
        this.savedAtEpochMs = savedAtEpochMs;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @NonNull public String getPayloadJson() { return payloadJson; }
    public long getSavedAtEpochMs() { return savedAtEpochMs; }
}
