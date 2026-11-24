package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/** One entry (a chapter or an episode) of a stored series, with the user's progress. Its media is never stored. */
@Entity(
        tableName = "series_entries",
        primaryKeys = {"source", "series_address", "address"},
        foreignKeys = @ForeignKey(
                entity = SeriesEntity.class,
                parentColumns = {"source", "address"},
                childColumns = {"source", "series_address"},
                onDelete = ForeignKey.CASCADE
        )
)
public final class SeriesEntryEntity {
    @NonNull @ColumnInfo(name = "source") private final String source;
    @NonNull @ColumnInfo(name = "series_address") private final String seriesAddress;
    @NonNull @ColumnInfo(name = "address") private final String address;
    @NonNull @ColumnInfo(name = "name") private final String name;
    @Nullable @ColumnInfo(name = "number") private final Float number;
    @Nullable @ColumnInfo(name = "translator_group") private final String translatorGroup;
    @Nullable @ColumnInfo(name = "uploaded_at_epoch_ms") private final Long uploadedAtEpochMs;
    /** The entry's place in its source's list, which runs newest first. */
    @ColumnInfo(name = "source_order") private final int sourceOrder;
    @ColumnInfo(name = "first_seen_at_epoch_ms") private final long firstSeenAtEpochMs;
    @ColumnInfo(name = "read") private final boolean read;
    /**
     * Where the user left, 0 before the entry was opened: a page from 1, or milliseconds for an episode. The columns are
     * named for pages, which came first; renaming them would need a schema upgrade for nothing.
     */
    @ColumnInfo(name = "last_page") private final long lastPage;
    /** The page count or duration that the position counts within. */
    @Nullable @ColumnInfo(name = "page_count") private final Long pageCount;
    /** When the user last read or watched in it; null before that was kept. */
    @Nullable @ColumnInfo(name = "last_read_at_epoch_ms") private final Long lastReadAtEpochMs;
    /** What its extension keeps with it, as JSON text; null when it keeps nothing. */
    @Nullable @ColumnInfo(name = "memo") private final String memo;

    public SeriesEntryEntity(
            @NonNull String source,
            @NonNull String seriesAddress,
            @NonNull String address,
            @NonNull String name,
            @Nullable Float number,
            @Nullable String translatorGroup,
            @Nullable Long uploadedAtEpochMs,
            int sourceOrder,
            long firstSeenAtEpochMs,
            boolean read,
            long lastPage,
            @Nullable Long pageCount,
            @Nullable Long lastReadAtEpochMs,
            @Nullable String memo
    ) {
        this.source = source;
        this.seriesAddress = seriesAddress;
        this.address = address;
        this.name = name;
        this.number = number;
        this.translatorGroup = translatorGroup;
        this.uploadedAtEpochMs = uploadedAtEpochMs;
        this.sourceOrder = sourceOrder;
        this.firstSeenAtEpochMs = firstSeenAtEpochMs;
        this.read = read;
        this.lastPage = lastPage;
        this.pageCount = pageCount;
        this.lastReadAtEpochMs = lastReadAtEpochMs;
        this.memo = memo;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getSeriesAddress() { return seriesAddress; }
    @NonNull public String getAddress() { return address; }
    @NonNull public String getName() { return name; }
    @Nullable public Float getNumber() { return number; }
    @Nullable public String getTranslatorGroup() { return translatorGroup; }
    @Nullable public Long getUploadedAtEpochMs() { return uploadedAtEpochMs; }
    public int getSourceOrder() { return sourceOrder; }
    public long getFirstSeenAtEpochMs() { return firstSeenAtEpochMs; }
    public boolean getRead() { return read; }
    public long getLastPage() { return lastPage; }
    @Nullable public Long getPageCount() { return pageCount; }
    @Nullable public Long getLastReadAtEpochMs() { return lastReadAtEpochMs; }
    @Nullable public String getMemo() { return memo; }
}
