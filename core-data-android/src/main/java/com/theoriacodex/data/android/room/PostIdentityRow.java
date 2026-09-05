package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;

public final class PostIdentityRow {
    @NonNull public final String source;
    @NonNull @ColumnInfo(name = "source_post_id") public final String sourcePostId;

    public PostIdentityRow(@NonNull String source, @NonNull String sourcePostId) {
        this.source = source;
        this.sourcePostId = sourcePostId;
    }
}
