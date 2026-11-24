package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface SeriesDao {
    @Query("SELECT series.*, (SELECT COUNT(*) FROM series_entries WHERE series_entries.source = series.source "
            + "AND series_entries.series_address = series.address AND series_entries.read = 0 "
            + "AND series_entries.source_order >= 0) AS unread_count, "
            // An entry without an upload date counts from when it was first seen, so a new one is never passed over.
            + "(SELECT MAX(COALESCE(uploaded_at_epoch_ms, first_seen_at_epoch_ms)) FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address "
            + "AND series_entries.source_order >= 0) AS last_updated_at_epoch_ms, "
            + "(SELECT MAX(last_read_at_epoch_ms) FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address) "
            + "AS last_read_at_epoch_ms, "
            + "(series.last_opened_entry IS NOT NULL OR EXISTS (SELECT 1 FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address "
            + "AND (series_entries.read = 1 OR series_entries.last_page > 0))) AS started "
            + "FROM series WHERE followed = 1 "
            + "ORDER BY CASE WHEN unread_count > 0 THEN 0 ELSE 1 END ASC, unread_count DESC, "
            + "title COLLATE NOCASE ASC, source ASC, address ASC")
    Flow<List<FollowedSeriesRow>> observeFollowed();

    @Query("SELECT * FROM series WHERE source = :source AND address = :address LIMIT 1")
    Flow<SeriesEntity> observeSeries(String source, String address);

    @Query("SELECT * FROM series WHERE source = :source AND address = :address LIMIT 1")
    SeriesEntity series(String source, String address);

    // An entry the source stopped listing is kept with a negative `source_order`; only `allEntries` returns those.
    @Query("SELECT * FROM series_entries WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0 "
            + "ORDER BY source_order ASC")
    Flow<List<SeriesEntryEntity>> observeEntries(String source, String seriesAddress);

    @Query("SELECT * FROM series_entries WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0 "
            + "ORDER BY source_order ASC")
    List<SeriesEntryEntity> entries(String source, String seriesAddress);

    @Query("SELECT * FROM series_entries WHERE source = :source AND series_address = :seriesAddress")
    List<SeriesEntryEntity> allEntries(String source, String seriesAddress);

    @Query("SELECT * FROM series_entries WHERE source = :source AND series_address = :seriesAddress AND address = :address LIMIT 1")
    SeriesEntryEntity entry(String source, String seriesAddress, String address);

    /** What a profile backup holds: the series the user follows, started reading, or chose a reading mode for. */
    @Query("SELECT * FROM series WHERE followed = 1 OR reading_mode IS NOT NULL OR last_opened_entry IS NOT NULL "
            + "ORDER BY source ASC, address ASC")
    List<SeriesEntity> keptSeries();

    /** Returns -1 when the series is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insertSeries(SeriesEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertEntries(List<SeriesEntryEntity> entities);

    /** A restore adds to a stored series without taking anything from it. */
    @Query("UPDATE series SET followed = followed OR :followed, reading_mode = COALESCE(reading_mode, :readingMode), "
            + "last_opened_entry = COALESCE(last_opened_entry, :lastOpenedEntry), download_new = download_new OR :downloadNew, "
            + "followed_at_epoch_ms = CASE WHEN followed = 1 THEN COALESCE(followed_at_epoch_ms, :followedAt) "
            + "WHEN :followed THEN :followedAt ELSE NULL END "
            + "WHERE source = :source AND address = :address")
    void mergeRestoredSeries(
            String source, String address, boolean followed, String readingMode, String lastOpenedEntry, boolean downloadNew,
            Long followedAt);

    /**
     * A stored entry takes the further progress of the two: read on either side, and the later page together with the
     * page count it was recorded against. When that side has no count, as a Mihon backup's pages never do, it takes the
     * other side's only if it reaches that page, so a page is never shown beyond its count. It keeps the later read time.
     */
    @Query("UPDATE series_entries SET read = read OR :read, last_page = MAX(last_page, :lastPage), "
            + "page_count = CASE WHEN :lastPage > last_page "
            + "THEN COALESCE(:pageCount, CASE WHEN page_count >= :lastPage THEN page_count END) "
            + "ELSE COALESCE(page_count, CASE WHEN :pageCount >= last_page THEN :pageCount END) END, "
            + "last_read_at_epoch_ms = MAX(COALESCE(last_read_at_epoch_ms, :lastReadAt), COALESCE(:lastReadAt, last_read_at_epoch_ms)) "
            + "WHERE source = :source AND series_address = :seriesAddress AND address = :address")
    void mergeRestoredEntry(
            String source, String seriesAddress, String address, boolean read, long lastPage, Long pageCount, Long lastReadAt);

    /** Its entries go with it, by the foreign key's cascade. */
    @Query("DELETE FROM series WHERE source = :source AND address = :address")
    void deleteSeries(String source, String address);

    @Query("UPDATE series_entries SET read = 1 WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0")
    void markAllRead(String source, String seriesAddress);

    @Query("UPDATE series_entries SET read = 0, last_page = 0 WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0")
    void markAllUnread(String source, String seriesAddress);

    @Query("UPDATE series SET last_opened_entry = NULL WHERE source = :source AND address = :address")
    void clearLastOpenedEntry(String source, String address);

    /** An update in place: replacing the row would cascade its entries away. */
    @Upsert void upsertSeries(SeriesEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertEntries(List<SeriesEntryEntity> entities);
}
