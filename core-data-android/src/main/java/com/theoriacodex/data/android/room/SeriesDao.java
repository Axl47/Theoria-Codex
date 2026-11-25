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
    String ENTRY_HAS_PROGRESS = "(series_entries.read = 1 OR series_entries.last_page > 0)";

    @Query("SELECT series.*, (SELECT COUNT(*) FROM series_entries WHERE series_entries.source = series.source "
            + "AND series_entries.series_address = series.address AND series_entries.read = 0 "
            + "AND series_entries.source_order >= 0) AS unread_count, "
            + "(SELECT MAX(COALESCE(uploaded_at_epoch_ms, first_seen_at_epoch_ms)) FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address "
            + "AND series_entries.source_order >= 0) AS last_updated_at_epoch_ms, "
            + "(SELECT MAX(last_read_at_epoch_ms) FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address) "
            + "AS last_read_at_epoch_ms, "
            + "(series.last_opened_entry IS NOT NULL OR EXISTS (SELECT 1 FROM series_entries "
            + "WHERE series_entries.source = series.source AND series_entries.series_address = series.address "
            + "AND " + ENTRY_HAS_PROGRESS + ")) AS started "
            + "FROM series WHERE followed = 1 "
            + "ORDER BY CASE WHEN unread_count > 0 THEN 0 ELSE 1 END ASC, unread_count DESC, "
            + "title COLLATE NOCASE ASC, source ASC, address ASC")
    Flow<List<FollowedSeriesRow>> observeFollowed();

    @Query("SELECT EXISTS (SELECT 1 FROM series WHERE followed = 1)")
    Flow<Boolean> observeHasFollowed();

    @Query("SELECT * FROM series WHERE source = :source AND address = :address LIMIT 1")
    Flow<SeriesEntity> observeSeries(String source, String address);

    @Query("SELECT * FROM series WHERE source = :source AND address = :address LIMIT 1")
    SeriesEntity series(String source, String address);

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

    @Query("SELECT * FROM series_entries WHERE source = :source AND series_address = :seriesAddress AND address = :address "
            + "AND source_order >= 0 LIMIT 1")
    SeriesEntryEntity listedEntry(String source, String seriesAddress, String address);

    @Query("SELECT * FROM series WHERE followed = 1 OR reading_mode IS NOT NULL OR last_opened_entry IS NOT NULL "
            + "OR EXISTS (SELECT 1 FROM series_entries WHERE series_entries.source = series.source "
            + "AND series_entries.series_address = series.address AND " + ENTRY_HAS_PROGRESS + ") "
            + "ORDER BY source ASC, address ASC")
    List<SeriesEntity> keptSeries();

    @Insert(onConflict = OnConflictStrategy.IGNORE) long insertSeries(SeriesEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertEntries(List<SeriesEntryEntity> entities);

    @Query("UPDATE series SET followed = followed OR :followed, reading_mode = COALESCE(reading_mode, :readingMode), "
            + "last_opened_entry = COALESCE(last_opened_entry, :lastOpenedEntry), download_new = download_new OR :downloadNew, "
            + "followed_at_epoch_ms = CASE WHEN followed = 1 THEN COALESCE(followed_at_epoch_ms, :followedAt) "
            + "WHEN :followed THEN :followedAt ELSE NULL END, memo = COALESCE(memo, :memo) "
            + "WHERE source = :source AND address = :address")
    void mergeRestoredSeries(
            String source, String address, boolean followed, String readingMode, String lastOpenedEntry, boolean downloadNew,
            Long followedAt, String memo);

    @Query("UPDATE series_entries SET read = read OR :read, last_page = MAX(last_page, :lastPage), "
            + "page_count = CASE WHEN :lastPage > last_page "
            + "THEN COALESCE(:pageCount, CASE WHEN page_count >= :lastPage THEN page_count END) "
            + "ELSE COALESCE(page_count, CASE WHEN :pageCount >= last_page THEN :pageCount END) END, "
            + "last_read_at_epoch_ms = MAX(COALESCE(last_read_at_epoch_ms, :lastReadAt), COALESCE(:lastReadAt, last_read_at_epoch_ms)), "
            + "page_offset = CASE WHEN :lastPage > last_page THEN :pageOffset "
            + "WHEN :lastPage = last_page THEN MAX(COALESCE(page_offset, :pageOffset), COALESCE(:pageOffset, page_offset)) ELSE page_offset END, "
            + "memo = COALESCE(memo, :memo) "
            + "WHERE source = :source AND series_address = :seriesAddress AND address = :address")
    void mergeRestoredEntry(
            String source, String seriesAddress, String address, boolean read, long lastPage, Long pageCount, Long lastReadAt,
            String memo, Float pageOffset);

    @Query("DELETE FROM series WHERE source = :source AND address = :address")
    void deleteSeries(String source, String address);

    @Query("UPDATE series_entries SET read = 1 WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0")
    void markAllRead(String source, String seriesAddress);

    @Query("UPDATE series_entries SET read = 0, last_page = 0, page_offset = NULL WHERE source = :source AND series_address = :seriesAddress AND source_order >= 0")
    void markAllUnread(String source, String seriesAddress);

    @Query("UPDATE series SET last_opened_entry = NULL WHERE source = :source AND address = :address")
    void clearLastOpenedEntry(String source, String address);

    @Upsert void upsertSeries(SeriesEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertEntries(List<SeriesEntryEntity> entities);
}
