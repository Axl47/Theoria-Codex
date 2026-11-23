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
    /**
     * Stores [phash] for a post the posts table holds, keeping what the tagger stored; a post that is not stored (any
     * more) is left alone.
     */
    @Query("INSERT INTO post_visual_signatures (source, source_post_id, phash, phash_checked, indexed_at_epoch_ms) "
            + "SELECT :source, :sourcePostId, :phash, 1, :indexedAtEpochMs WHERE EXISTS "
            + "(SELECT 1 FROM posts WHERE source = :source AND source_post_id = :sourcePostId) "
            + "ON CONFLICT(source, source_post_id) DO UPDATE SET phash = excluded.phash, phash_checked = 1")
    void put(String source, String sourcePostId, Long phash, long indexedAtEpochMs);

    /**
     * Stores what the tagger saw for a post the posts table holds; a new row has no hash tried yet and an existing
     * hash is kept. A post that is not stored (any more) is left alone.
     */
    @Query("INSERT INTO post_visual_signatures (source, source_post_id, phash, phash_checked, indexed_at_epoch_ms, "
            + "model_id, embedding, predicted_tags) "
            + "SELECT :source, :sourcePostId, NULL, 0, :indexedAtEpochMs, :modelId, :embedding, :predictedTags WHERE EXISTS "
            + "(SELECT 1 FROM posts WHERE source = :source AND source_post_id = :sourcePostId) "
            + "ON CONFLICT(source, source_post_id) DO UPDATE SET model_id = excluded.model_id, "
            + "embedding = excluded.embedding, predicted_tags = excluded.predicted_tags")
    void putVision(String source, String sourcePostId, long indexedAtEpochMs, String modelId,
            @Nullable byte[] embedding, @Nullable String predictedTags);

    /** Only the hash columns: rows can carry an embedding that listing hashes must not load. */
    @Query("SELECT source, source_post_id, phash FROM post_visual_signatures WHERE phash IS NOT NULL")
    Flow<List<HashRow>> observeHashed();

    @Query("SELECT source, source_post_id, predicted_tags FROM post_visual_signatures "
            + "WHERE model_id = :modelId AND predicted_tags IS NOT NULL")
    Flow<List<PredictedTagsRow>> observePredictedTags(String modelId);

    /** Liked or saved posts whose hash was not tried yet, most recently stored first. */
    @Query("SELECT posts.* FROM posts WHERE NOT EXISTS (SELECT 1 FROM post_visual_signatures AS signature "
            + "WHERE signature.source = posts.source AND signature.source_post_id = posts.source_post_id "
            + "AND signature.phash_checked = 1) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit")
    List<PostEntity> libraryPostsWithoutSignature(int limit);

    /** Liked or saved posts that [modelId] has not analysed, most recently stored first, skipping the first [offset]. */
    @Query("SELECT posts.* FROM posts WHERE NOT EXISTS (SELECT 1 FROM post_visual_signatures AS signature "
            + "WHERE signature.source = posts.source AND signature.source_post_id = posts.source_post_id "
            + "AND signature.model_id = :modelId) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit OFFSET :offset")
    List<PostEntity> libraryPostsWithoutVision(String modelId, int limit, int offset);

    /** Liked or saved posts [modelId] has analysed, and how many liked or saved posts there are. */
    @Query("SELECT (SELECT COUNT(*) FROM post_visual_signatures AS signature WHERE signature.model_id = :modelId "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = signature.source "
            + "AND codex_items.source_post_id = signature.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = signature.source "
            + "AND liked_posts.source_post_id = signature.source_post_id))) AS analysed, "
            + "(SELECT COUNT(*) FROM posts WHERE EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) AS total")
    Flow<ProgressRow> observeVisionProgress(String modelId);

    @Query("UPDATE post_visual_signatures SET model_id = NULL, embedding = NULL, predicted_tags = NULL")
    void clearVision();

    /** Rows the tagger made before any hash was tried hold nothing once the tagger's data is gone. */
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
