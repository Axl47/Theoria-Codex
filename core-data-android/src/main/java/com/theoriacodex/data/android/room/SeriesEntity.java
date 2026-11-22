package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/** A series the user opened: what its source said, and whether they follow it and how they read it. */
@Entity(tableName = "series", primaryKeys = {"source", "address"})
public final class SeriesEntity {
    @NonNull @ColumnInfo(name = "source") private final String source;
    @NonNull @ColumnInfo(name = "address") private final String address;
    @NonNull @ColumnInfo(name = "title") private final String title;
    @Nullable @ColumnInfo(name = "cover_url") private final String coverUrl;
    @Nullable @ColumnInfo(name = "author") private final String author;
    /** One genre per line. */
    @NonNull @ColumnInfo(name = "genres") private final String genres;
    @NonNull @ColumnInfo(name = "language") private final String language;
    @ColumnInfo(name = "followed") private final boolean followed;
    /** The mode the user chose; null follows the guess. */
    @Nullable @ColumnInfo(name = "reading_mode") private final String readingMode;
    @Nullable @ColumnInfo(name = "last_opened_entry") private final String lastOpenedEntry;
    @Nullable @ColumnInfo(name = "last_checked_at_epoch_ms") private final Long lastCheckedAtEpochMs;

    public SeriesEntity(
            @NonNull String source,
            @NonNull String address,
            @NonNull String title,
            @Nullable String coverUrl,
            @Nullable String author,
            @NonNull String genres,
            @NonNull String language,
            boolean followed,
            @Nullable String readingMode,
            @Nullable String lastOpenedEntry,
            @Nullable Long lastCheckedAtEpochMs
    ) {
        this.source = source;
        this.address = address;
        this.title = title;
        this.coverUrl = coverUrl;
        this.author = author;
        this.genres = genres;
        this.language = language;
        this.followed = followed;
        this.readingMode = readingMode;
        this.lastOpenedEntry = lastOpenedEntry;
        this.lastCheckedAtEpochMs = lastCheckedAtEpochMs;
    }

    @NonNull public String getSource() { return source; }
    @NonNull public String getAddress() { return address; }
    @NonNull public String getTitle() { return title; }
    @Nullable public String getCoverUrl() { return coverUrl; }
    @Nullable public String getAuthor() { return author; }
    @NonNull public String getGenres() { return genres; }
    @NonNull public String getLanguage() { return language; }
    public boolean getFollowed() { return followed; }
    @Nullable public String getReadingMode() { return readingMode; }
    @Nullable public String getLastOpenedEntry() { return lastOpenedEntry; }
    @Nullable public Long getLastCheckedAtEpochMs() { return lastCheckedAtEpochMs; }
}
