package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface MediaDurationDao {
    @Query("SELECT * FROM media_durations WHERE source = :source "
            + "AND source_post_id = :sourcePostId AND media_fingerprint = :mediaFingerprint LIMIT 1")
    MediaDurationEntity find(String source, String sourcePostId, String mediaFingerprint);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MediaDurationEntity entity);

    @Query("DELETE FROM media_durations WHERE source = :source "
            + "AND source_post_id = :sourcePostId AND media_fingerprint = :mediaFingerprint")
    int delete(String source, String sourcePostId, String mediaFingerprint);

    @Query("DELETE FROM media_durations WHERE (source, source_post_id, media_fingerprint) IN "
            + "(SELECT source, source_post_id, media_fingerprint FROM media_durations "
            + "ORDER BY updated_at_epoch_ms DESC, source ASC, source_post_id ASC, "
            + "media_fingerprint ASC LIMIT -1 OFFSET :limit)")
    int trimToLimit(int limit);

    @Query("SELECT COUNT(*) FROM media_durations")
    int count();
}
