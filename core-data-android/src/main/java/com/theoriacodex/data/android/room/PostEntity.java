package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(tableName = "posts", primaryKeys = {"source", "source_post_id"})
public final class PostEntity {
    @NonNull
    private final String source;
    @NonNull
    @ColumnInfo(name = "source_post_id")
    private final String sourcePostId;
    @NonNull
    @ColumnInfo(name = "payload_json")
    private final String payloadJson;

    public PostEntity(
            @NonNull String source,
            @NonNull String sourcePostId,
            @NonNull String payloadJson
    ) {
        this.source = source;
        this.sourcePostId = sourcePostId;
        this.payloadJson = payloadJson;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSourcePostId() { return sourcePostId; }
    @NonNull public String getPayloadJson() { return payloadJson; }
}
