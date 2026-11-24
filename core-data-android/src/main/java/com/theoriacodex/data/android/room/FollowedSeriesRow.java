package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/**
 * A followed series with how many of its entries are unread, when its newest came out, when one was last read, and
 * whether any was opened.
 */
public final class FollowedSeriesRow {
    @NonNull @Embedded public final SeriesEntity series;
    @ColumnInfo(name = "unread_count") public final int unreadCount;
    @Nullable @ColumnInfo(name = "last_updated_at_epoch_ms") public final Long lastUpdatedAtEpochMs;
    @Nullable @ColumnInfo(name = "last_read_at_epoch_ms") public final Long lastReadAtEpochMs;
    @ColumnInfo(name = "started") public final boolean started;

    public FollowedSeriesRow(
            @NonNull SeriesEntity series,
            int unreadCount,
            @Nullable Long lastUpdatedAtEpochMs,
            @Nullable Long lastReadAtEpochMs,
            boolean started
    ) {
        this.series = series;
        this.unreadCount = unreadCount;
        this.lastUpdatedAtEpochMs = lastUpdatedAtEpochMs;
        this.lastReadAtEpochMs = lastReadAtEpochMs;
        this.started = started;
    }
}
