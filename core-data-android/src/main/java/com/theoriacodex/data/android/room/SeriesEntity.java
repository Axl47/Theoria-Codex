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
    @Nullable @ColumnInfo(name = "description") private final String description;
    /** A `SeriesStatus` name; null while the source has not said. */
    @Nullable @ColumnInfo(name = "status") private final String status;
    /** Whether new chapters the background check finds are downloaded. */
    @ColumnInfo(name = "download_new", defaultValue = "0") private final boolean downloadNew;

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
            @Nullable Long lastCheckedAtEpochMs,
            @Nullable String description,
            @Nullable String status,
            boolean downloadNew
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
        this.description = description;
        this.status = status;
        this.downloadNew = downloadNew;
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
    @Nullable public String getDescription() { return description; }
    @Nullable public String getStatus() { return status; }
    public boolean getDownloadNew() { return downloadNew; }
}
