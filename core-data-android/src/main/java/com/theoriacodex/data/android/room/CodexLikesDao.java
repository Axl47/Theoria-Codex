package com.theoriacodex.data.android.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface CodexLikesDao {
    @Query("SELECT * FROM codices ORDER BY display_order ASC, created_at_epoch_ms ASC, codex_id ASC")
    Flow<List<CodexEntity>> observeCodices();

    @Query("SELECT * FROM codices ORDER BY display_order ASC, created_at_epoch_ms ASC, codex_id ASC")
    List<CodexEntity> codices();

    @Query("SELECT * FROM codices WHERE codex_id = :codexId LIMIT 1")
    Flow<CodexEntity> observeCodex(String codexId);

    @Query("SELECT * FROM codices WHERE codex_id = :codexId LIMIT 1")
    CodexEntity codex(String codexId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertCodex(CodexEntity entity);

    @Query("UPDATE codices SET name = :name WHERE codex_id = :codexId")
    int updateCodexName(String codexId, String name);

    @Query("UPDATE codices SET display_order = :displayOrder WHERE codex_id = :codexId")
    int updateCodexOrder(String codexId, int displayOrder);

    @Query("DELETE FROM codices WHERE codex_id = :codexId")
    int deleteCodex(String codexId);

    @Query("DELETE FROM codices")
    int deleteAllCodices();

    @Query("SELECT * FROM codex_automatic_tags ORDER BY codex_id ASC, source ASC, group_index ASC, tag_key ASC")
    Flow<List<CodexAutomaticTagEntity>> observeAutomaticTags();

    @Query("SELECT * FROM codex_automatic_tags WHERE codex_id = :codexId "
            + "ORDER BY source ASC, group_index ASC, tag_key ASC")
    List<CodexAutomaticTagEntity> automaticTagsForCodex(String codexId);

    @Query("SELECT * FROM codex_automatic_tags WHERE codex_id IN (:codexIds) "
            + "ORDER BY codex_id ASC, source ASC, group_index ASC, tag_key ASC")
    List<CodexAutomaticTagEntity> automaticTagsForCodices(List<String> codexIds);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAutomaticTags(List<CodexAutomaticTagEntity> entities);

    @Query("DELETE FROM codex_automatic_tags WHERE codex_id = :codexId")
    int deleteAutomaticTags(String codexId);

    @Query("SELECT codex_id, source, source_post_id, saved_at_epoch_ms FROM codex_items "
            + "WHERE codex_id = :codexId "
            + "ORDER BY saved_at_epoch_ms DESC, source ASC, source_post_id ASC")
    Flow<List<CodexItemEntity>> observeCodexItems(String codexId);

    @Query("SELECT posts.source, posts.source_post_id, posts.payload_json, codex_items.saved_at_epoch_ms "
            + "FROM codex_items INNER JOIN posts "
            + "ON posts.source = codex_items.source "
            + "AND posts.source_post_id = codex_items.source_post_id "
            + "WHERE codex_items.codex_id = :codexId "
            + "ORDER BY codex_items.saved_at_epoch_ms DESC, posts.source ASC, posts.source_post_id ASC")
    Flow<List<CodexPostRow>> observeCodexPostsNewest(String codexId);

    @Query("SELECT posts.source, posts.source_post_id, posts.payload_json, codex_items.saved_at_epoch_ms "
            + "FROM codex_items INNER JOIN posts "
            + "ON posts.source = codex_items.source "
            + "AND posts.source_post_id = codex_items.source_post_id "
            + "WHERE codex_items.codex_id = :codexId "
            + "ORDER BY codex_items.saved_at_epoch_ms ASC, posts.source ASC, posts.source_post_id ASC")
    Flow<List<CodexPostRow>> observeCodexPostsOldest(String codexId);

    @Query("SELECT posts.source, posts.source_post_id, posts.payload_json, codex_items.saved_at_epoch_ms "
            + "FROM codex_items INNER JOIN posts "
            + "ON posts.source = codex_items.source "
            + "AND posts.source_post_id = codex_items.source_post_id "
            + "WHERE codex_items.codex_id = :codexId "
            + "ORDER BY posts.source ASC, codex_items.saved_at_epoch_ms DESC, posts.source_post_id ASC")
    Flow<List<CodexPostRow>> observeCodexPostsBySource(String codexId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertCodexItem(CodexItemEntity entity);

    @Query("SELECT * FROM codex_items WHERE codex_id = :codexId AND source = :source "
            + "AND source_post_id = :sourcePostId LIMIT 1")
    CodexItemEntity codexItem(String codexId, String source, String sourcePostId);

    @Query("DELETE FROM codex_items WHERE codex_id = :codexId "
            + "AND source = :source AND source_post_id = :sourcePostId")
    int deleteCodexItem(String codexId, String source, String sourcePostId);

    @Query("DELETE FROM codex_items WHERE codex_id = :codexId")
    int deleteCodexItems(String codexId);

    @Query("SELECT * FROM codex_items ORDER BY codex_id ASC, source ASC, source_post_id ASC")
    List<CodexItemEntity> codexItems();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertPost(PostEntity entity);

    @Query("UPDATE posts SET payload_json = :payloadJson "
            + "WHERE source = :source AND source_post_id = :sourcePostId")
    int updatePost(String source, String sourcePostId, String payloadJson);

    @Query("SELECT * FROM posts WHERE source = :source AND source_post_id = :sourcePostId LIMIT 1")
    PostEntity post(String source, String sourcePostId);

    @Query("SELECT * FROM posts ORDER BY source ASC, source_post_id ASC")
    List<PostEntity> posts();

    @Query("DELETE FROM posts WHERE "
            + "NOT EXISTS (SELECT 1 FROM codex_items "
            + "WHERE codex_items.source = posts.source "
            + "AND codex_items.source_post_id = posts.source_post_id) "
            + "AND NOT EXISTS (SELECT 1 FROM liked_posts "
            + "WHERE liked_posts.source = posts.source "
            + "AND liked_posts.source_post_id = posts.source_post_id) "
            + "AND NOT EXISTS (SELECT 1 FROM recent_watched "
            + "WHERE recent_watched.source = posts.source "
            + "AND recent_watched.source_post_id = posts.source_post_id)")
    int deleteOrphanPosts();

    @Query("DELETE FROM posts")
    int deleteAllPosts();

    @Query("SELECT * FROM liked_posts WHERE profile_id = :profileId "
            + "ORDER BY liked_at_epoch_ms DESC, source ASC, source_post_id ASC")
    Flow<List<LikedPostEntity>> observeLikes(String profileId);

    @Query("SELECT * FROM liked_posts WHERE profile_id = :profileId "
            + "ORDER BY liked_at_epoch_ms DESC, source ASC, source_post_id ASC")
    List<LikedPostEntity> likesForProfile(String profileId);

    @Query("SELECT * FROM liked_posts "
            + "ORDER BY profile_id ASC, source ASC, source_post_id ASC")
    List<LikedPostEntity> allLikes();

    @Query("SELECT * FROM liked_posts WHERE profile_id = :profileId "
            + "AND source = :source AND source_post_id = :sourcePostId LIMIT 1")
    LikedPostEntity likedPost(String profileId, String source, String sourcePostId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertLike(LikedPostEntity entity);

    @Query("UPDATE liked_posts SET liked_at_epoch_ms = :likedAtEpochMs, tags_json = :tagsJson "
            + "WHERE profile_id = :profileId AND source = :source AND source_post_id = :sourcePostId")
    int updateLike(
            String profileId,
            String source,
            String sourcePostId,
            long likedAtEpochMs,
            String tagsJson
    );

    @Query("DELETE FROM liked_posts WHERE profile_id = :profileId "
            + "AND source = :source AND source_post_id = :sourcePostId")
    int deleteLike(String profileId, String source, String sourcePostId);

    @Query("DELETE FROM liked_posts WHERE profile_id = :profileId")
    int deleteLikes(String profileId);

    @Query("DELETE FROM liked_posts")
    int deleteAllLikes();

    @Query("SELECT * FROM migration_metadata WHERE migration_key = :migrationKey LIMIT 1")
    MigrationMetadataEntity migrationMetadata(String migrationKey);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertMigrationMetadata(MigrationMetadataEntity entity);

    @Query("UPDATE migration_metadata SET codex_archived = :codexArchived, "
            + "likes_archived = :likesArchived WHERE migration_key = :migrationKey")
    int updateMigrationArchiveState(
            String migrationKey,
            boolean codexArchived,
            boolean likesArchived
    );
}
