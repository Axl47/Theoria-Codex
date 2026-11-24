package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ForYouVerdictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ForYouVerdictEntity entity);

    /** Deletes the verdict decided at {@code decidedAtEpochMs}; a later swipe on the post stays. */
    @Query("DELETE FROM for_you_verdicts WHERE profile_id = :profileId "
            + "AND source = :source AND source_post_id = :sourcePostId AND decided_at_epoch_ms = :decidedAtEpochMs")
    int delete(String profileId, String source, String sourcePostId, long decidedAtEpochMs);

    @Query("DELETE FROM for_you_verdicts WHERE profile_id = :profileId")
    int deleteProfile(String profileId);

    /** Keeps the profile's newest [limit] verdicts; uses the (profile_id, decided_at_epoch_ms) index. */
    @Query("DELETE FROM for_you_verdicts WHERE profile_id = :profileId AND (source, source_post_id) IN "
            + "(SELECT source, source_post_id FROM for_you_verdicts WHERE profile_id = :profileId "
            + "ORDER BY decided_at_epoch_ms DESC LIMIT -1 OFFSET :limit)")
    int trimToLimit(String profileId, int limit);

    @Query("SELECT * FROM for_you_verdicts WHERE profile_id = :profileId "
            + "ORDER BY decided_at_epoch_ms DESC, source ASC, source_post_id ASC")
    List<ForYouVerdictEntity> forProfile(String profileId);

    /**
     * The profile's newest {@code limit} verdicts of {@code decision} decided at or after {@code sinceEpochMs}; uses the
     * (profile_id, decided_at_epoch_ms) index.
     */
    @Query("SELECT * FROM for_you_verdicts WHERE profile_id = :profileId AND decision = :decision "
            + "AND decided_at_epoch_ms >= :sinceEpochMs ORDER BY decided_at_epoch_ms DESC LIMIT :limit")
    List<ForYouVerdictEntity> since(String profileId, String decision, long sinceEpochMs, int limit);
}
