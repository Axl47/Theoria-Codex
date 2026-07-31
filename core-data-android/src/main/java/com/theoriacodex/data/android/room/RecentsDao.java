package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface RecentsDao {
    @Query("SELECT posts.source, posts.source_post_id, posts.payload_json, recent_watched.viewed_at_epoch_ms, recent_watched.sort_sequence, recent_watched.origin, recent_watched.origin_query_hash "
            + "FROM recent_watched INNER JOIN posts ON posts.source = recent_watched.source AND posts.source_post_id = recent_watched.source_post_id "
            + "ORDER BY recent_watched.viewed_at_epoch_ms DESC, recent_watched.sort_sequence DESC, posts.source ASC, posts.source_post_id ASC")
    Flow<List<RecentWatchedRow>> observeWatched();
    @Query("SELECT * FROM recent_watched ORDER BY viewed_at_epoch_ms DESC, sort_sequence DESC, source ASC, source_post_id ASC")
    List<RecentWatchedEntity> watched();
    @Query("SELECT * FROM recent_watched WHERE source = :source AND source_post_id = :postId LIMIT 1")
    RecentWatchedEntity watched(String source, String postId);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertWatched(RecentWatchedEntity entity);
    @Query("SELECT COALESCE(MAX(sort_sequence), 0) + 1 FROM recent_watched") long nextWatchedSequence();
    @Query("DELETE FROM recent_watched WHERE (source, source_post_id) IN (SELECT source, source_post_id FROM recent_watched ORDER BY viewed_at_epoch_ms DESC, sort_sequence DESC, source ASC, source_post_id ASC LIMIT -1 OFFSET :limit)")
    int trimWatched(int limit);
    @Query("DELETE FROM recent_watched") int deleteWatched();
    @Query("DELETE FROM recent_watched WHERE origin = :origin") int deleteWatchedOrigin(String origin);
    @Query("DELETE FROM recent_watched WHERE origin != :origin") int deleteWatchedExceptOrigin(String origin);

    @Query("SELECT * FROM recent_searches ORDER BY searched_at_epoch_ms DESC, sort_sequence DESC, query_hash ASC")
    Flow<List<RecentSearchEntity>> observeSearches();
    @Query("SELECT * FROM recent_searches ORDER BY searched_at_epoch_ms DESC, sort_sequence DESC, query_hash ASC")
    List<RecentSearchEntity> searches();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertSearch(RecentSearchEntity entity);
    @Query("SELECT COALESCE(MAX(sort_sequence), 0) + 1 FROM recent_searches") long nextSearchSequence();
    @Query("DELETE FROM recent_searches WHERE query_hash IN (SELECT query_hash FROM recent_searches ORDER BY searched_at_epoch_ms DESC, sort_sequence DESC, query_hash ASC LIMIT -1 OFFSET :limit)")
    int trimSearches(int limit);
    @Query("DELETE FROM recent_searches") int deleteSearches();

    @Query("SELECT * FROM recents_migration_metadata WHERE migration_key = :key LIMIT 1")
    RecentsMigrationEntity migration(String key);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertMigration(RecentsMigrationEntity entity);
    @Query("UPDATE recents_migration_metadata SET source_archived = 1 WHERE migration_key = :key")
    int markArchived(String key);
}
