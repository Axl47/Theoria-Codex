package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

public final class RecentWatchedRow {
    @NonNull public String source;
    @NonNull @ColumnInfo(name = "source_post_id") public String sourcePostId;
    @NonNull public String section;
    @NonNull @ColumnInfo(name = "payload_json") public String payloadJson;
    @ColumnInfo(name = "viewed_at_epoch_ms") public long viewedAtEpochMs;
    @ColumnInfo(name = "sort_sequence") public long sortSequence;
    @NonNull public String origin;
    @Nullable @ColumnInfo(name = "origin_query_hash") public String originQueryHash;
}
