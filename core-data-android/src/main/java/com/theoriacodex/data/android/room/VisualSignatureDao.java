package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Query;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface VisualSignatureDao {
    /** Stores [phash] for a post the posts table holds; a post that is not stored (any more) is left alone. */
    @Query("INSERT OR REPLACE INTO post_visual_signatures (source, source_post_id, phash, indexed_at_epoch_ms) "
            + "SELECT :source, :sourcePostId, :phash, :indexedAtEpochMs WHERE EXISTS "
            + "(SELECT 1 FROM posts WHERE source = :source AND source_post_id = :sourcePostId)")
    void put(String source, String sourcePostId, Long phash, long indexedAtEpochMs);

    @Query("SELECT * FROM post_visual_signatures WHERE phash IS NOT NULL")
    Flow<List<VisualSignatureEntity>> observeHashed();

    /** Liked or saved posts with no signature yet, most recently stored first. */
    @Query("SELECT posts.* FROM posts WHERE NOT EXISTS (SELECT 1 FROM post_visual_signatures AS signature "
            + "WHERE signature.source = posts.source AND signature.source_post_id = posts.source_post_id) "
            + "AND (EXISTS (SELECT 1 FROM codex_items WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "OR EXISTS (SELECT 1 FROM liked_posts WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id)) "
            + "ORDER BY posts.rowid DESC LIMIT :limit")
    List<PostEntity> libraryPostsWithoutSignature(int limit);
}
