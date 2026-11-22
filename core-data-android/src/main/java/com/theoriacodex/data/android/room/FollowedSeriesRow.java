package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/** A followed series with how many of its entries are unread. */
public final class FollowedSeriesRow {
    @NonNull @Embedded public final SeriesEntity series;
    @ColumnInfo(name = "unread_count") public final int unreadCount;

    public FollowedSeriesRow(@NonNull SeriesEntity series, int unreadCount) {
        this.series = series;
        this.unreadCount = unreadCount;
    }
}
