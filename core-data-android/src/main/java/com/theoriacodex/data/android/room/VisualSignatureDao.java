package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Query;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface VisualSignatureDao {
    @Query("INSERT INTO post_visual_signatures (source, source_post_id, phash, phash_checked, indexed_at_epoch_ms) "
            + "SELECT :source, :sourcePostId, :phash, 1, :indexedAtEpochMs WHERE EXISTS "
            + "(SELECT 1 FROM posts WHERE source = :source AND source_post_id = :sourcePostId) "
            + "ON CONFLICT(source, source_post_id) DO UPDATE SET phash = excluded.phash, phash_checked = 1")
    void put(String source, String sourcePostId, Long phash, long indexedAtEpochMs);

    @Query("INSERT INTO post_visual_signatures (source, source_post_id, phash, phash_checked, indexed_at_epoch_ms, "
            + "model_id, embedding, predicted_tags, frame_state, frame_attempts) "
            + "SELECT :source, :sourcePostId, NULL, 0, :indexedAtEpochMs, :modelId, :embedding, :predictedTags, :frameState, 0 "
            + "WHERE EXISTS (SELECT 1 FROM posts WHERE source = :source AND source_post_id = :sourcePostId) "
            + "ON CONFLICT(source, source_post_id) DO UPDATE SET model_id = excluded.model_id, "
            + "embedding = excluded.embedding, predicted_tags = excluded.predicted_tags, "
            + "frame_state = excluded.frame_state, frame_attempts = 0")
    void putVision(String source, String sourcePostId, long indexedAtEpochMs, String modelId,
            @Nullable byte[] embedding, @Nullable String predictedTags, int frameState);

    @Query("UPDATE post_visual_signatures SET frame_state = :frameState "
            + "WHERE source = :source AND source_post_id = :sourcePostId AND model_id = :modelId")
    void setFrameState(String source, String sourcePostId, String modelId, int frameState);

    @Query("UPDATE post_visual_signatures SET frame_attempts = frame_attempts + :attempts "
            + "WHERE source = :source AND source_post_id = :sourcePostId AND model_id = :modelId")
    void addFrameAttempts(String source, String sourcePostId, String modelId, int attempts);

    @Query("SELECT source, source_post_id, phash FROM post_visual_signatures WHERE phash IS NOT NULL")
    Flow<List<HashRow>> observeHashed();

    @Query("SELECT source, source_post_id, predicted_tags FROM post_visual_signatures "
            + "WHERE model_id = :modelId AND predicted_tags IS NOT NULL")
    Flow<List<PredictedTagsRow>> observePredictedTags(String modelId);

    @Query("SELECT posts.* FROM posts WHERE NOT EXISTS (SELECT 1 FROM post_visual_signatures AS signature "
            + "WHERE signature.source = posts.source AND signature.source_post_id = posts.source_post_id "
            + "AND signature.phash_checked = 1) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit")
    List<PostEntity> libraryPostsWithoutSignature(int limit);

    @Query("SELECT posts.* FROM posts WHERE NOT EXISTS (SELECT 1 FROM post_visual_signatures AS signature "
            + "WHERE signature.source = posts.source AND signature.source_post_id = posts.source_post_id "
            + "AND signature.model_id = :modelId) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit")
    List<PostEntity> libraryPostsWithoutVision(String modelId, int limit);

    @Query("SELECT posts.* FROM posts JOIN post_visual_signatures AS signature "
            + "ON signature.source = posts.source AND signature.source_post_id = posts.source_post_id "
            + "WHERE signature.model_id = :modelId AND signature.frame_state = 1 AND signature.frame_attempts < :maxAttempts "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit OFFSET :skip")
    List<PostEntity> libraryPostsAwaitingFrames(String modelId, int maxAttempts, int limit, int skip);

    @Query("SELECT (SELECT COUNT(*) FROM post_visual_signatures AS signature WHERE signature.model_id = :modelId "
            + "AND NOT (signature.frame_state = 1 AND signature.frame_attempts < :maxAttempts) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = signature.source "
            + "AND codex_items.source_post_id = signature.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = signature.source "
            + "AND liked_posts.source_post_id = signature.source_post_id))) AS analysed, "
            + "(SELECT COUNT(*) FROM posts WHERE EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) AS total")
    Flow<ProgressRow> observeVisionProgress(String modelId, int maxAttempts);

    @Query("UPDATE post_visual_signatures SET model_id = NULL, embedding = NULL, predicted_tags = NULL, frame_state = 0, "
            + "frame_attempts = 0")
    void clearVision();

    @Query("DELETE FROM post_visual_signatures WHERE phash_checked = 0")
    void deleteUnhashedRows();

    final class HashRow {
        @NonNull public final String source;
        @NonNull @ColumnInfo(name = "source_post_id") public final String sourcePostId;
        public final long phash;

        public HashRow(@NonNull String source, @NonNull String sourcePostId, long phash) {
            this.source = source;
            this.sourcePostId = sourcePostId;
            this.phash = phash;
        }
    }

    final class PredictedTagsRow {
        @NonNull public final String source;
        @NonNull @ColumnInfo(name = "source_post_id") public final String sourcePostId;
        @NonNull @ColumnInfo(name = "predicted_tags") public final String predictedTags;

        public PredictedTagsRow(
                @NonNull String source,
                @NonNull String sourcePostId,
                @NonNull String predictedTags
        ) {
            this.source = source;
            this.sourcePostId = sourcePostId;
            this.predictedTags = predictedTags;
        }
    }

    final class ProgressRow {
        public final int analysed;
        public final int total;

        public ProgressRow(int analysed, int total) {
            this.analysed = analysed;
            this.total = total;
        }
    }
}
