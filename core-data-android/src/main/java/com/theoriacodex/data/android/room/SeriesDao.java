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
            + "AND series_entries.source_order >= 0) AS unread_count "
            + "FROM series WHERE followed = 1 ORDER BY title COLLATE NOCASE ASC, source ASC, address ASC")
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
    @Query("UPDATE series SET followed = followed OR :followed, reading_mode = COALESCE(reading_mode, :readingMode) "
            + "WHERE source = :source AND address = :address")
    void mergeRestoredSeries(String source, String address, boolean followed, String readingMode);

    /** An update in place: replacing the row would cascade its entries away. */
    @Upsert void upsertSeries(SeriesEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertEntries(List<SeriesEntryEntity> entities);
}
