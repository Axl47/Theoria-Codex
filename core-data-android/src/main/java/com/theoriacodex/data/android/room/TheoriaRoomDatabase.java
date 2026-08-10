package com.theoriacodex.data.android.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                CodexEntity.class,
                CodexAutomaticTagEntity.class,
                PostEntity.class,
                CodexItemEntity.class,
                LikedPostEntity.class,
                MigrationMetadataEntity.class,
                RecentWatchedEntity.class,
                RecentSearchEntity.class,
                RecentsMigrationEntity.class
        },
        version = 4,
        exportSchema = true
)
public abstract class TheoriaRoomDatabase extends RoomDatabase {
    public static final String DEFAULT_DATABASE_NAME = "theoria_content.db";

    /**
     * Explicit migrations only. A future schema bump must add and test a migration here; this
     * database intentionally never opts into destructive fallback.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `recent_watched` (`source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `viewed_at_epoch_ms` INTEGER NOT NULL, `sort_sequence` INTEGER NOT NULL, `origin` TEXT NOT NULL, `origin_query_hash` TEXT, PRIMARY KEY(`source`, `source_post_id`), FOREIGN KEY(`source`, `source_post_id`) REFERENCES `posts`(`source`, `source_post_id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_watched_viewed_at_epoch_ms_sort_sequence_source_source_post_id` ON `recent_watched` (`viewed_at_epoch_ms`, `sort_sequence`, `source`, `source_post_id`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`query_hash` TEXT NOT NULL, `query_payload_json` TEXT NOT NULL, `searched_at_epoch_ms` INTEGER NOT NULL, `sort_sequence` INTEGER NOT NULL, PRIMARY KEY(`query_hash`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_searches_searched_at_epoch_ms_sort_sequence_query_hash` ON `recent_searches` (`searched_at_epoch_ms`, `sort_sequence`, `query_hash`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `recents_migration_metadata` (`migration_key` TEXT NOT NULL, `source_sha256` TEXT NOT NULL, `source_byte_count` INTEGER NOT NULL, `source_present` INTEGER NOT NULL, `source_quarantined` INTEGER NOT NULL, `destination_sha256` TEXT NOT NULL, `watched_count` INTEGER NOT NULL, `search_count` INTEGER NOT NULL, `proof_sha256` TEXT NOT NULL, `completed_at_epoch_ms` INTEGER NOT NULL, `source_archived` INTEGER NOT NULL, PRIMARY KEY(`migration_key`))");
        }
    };
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `recent_watched_new` (`source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `section` TEXT NOT NULL, `viewed_at_epoch_ms` INTEGER NOT NULL, `sort_sequence` INTEGER NOT NULL, `origin` TEXT NOT NULL, `origin_query_hash` TEXT, PRIMARY KEY(`source`, `source_post_id`, `section`), FOREIGN KEY(`source`, `source_post_id`) REFERENCES `posts`(`source`, `source_post_id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("INSERT INTO `recent_watched_new` (`source`, `source_post_id`, `section`, `viewed_at_epoch_ms`, `sort_sequence`, `origin`, `origin_query_hash`) SELECT `source`, `source_post_id`, CASE WHEN `origin` = 'CODEX' THEN 'CODEX' ELSE 'WATCHED' END, `viewed_at_epoch_ms`, `sort_sequence`, `origin`, `origin_query_hash` FROM `recent_watched`");
            database.execSQL("DROP TABLE `recent_watched`");
            database.execSQL("ALTER TABLE `recent_watched_new` RENAME TO `recent_watched`");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_watched_viewed_at_epoch_ms_sort_sequence_source_source_post_id_section` ON `recent_watched` (`viewed_at_epoch_ms`, `sort_sequence`, `source`, `source_post_id`, `section`)");
        }
    };
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `codex_automatic_tags` (`codex_id` TEXT NOT NULL, `source` TEXT NOT NULL, `tag_key` TEXT NOT NULL, `tag_display` TEXT NOT NULL, PRIMARY KEY(`codex_id`, `source`, `tag_key`), FOREIGN KEY(`codex_id`) REFERENCES `codices`(`codex_id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_codex_automatic_tags_codex_id` ON `codex_automatic_tags` (`codex_id`)");
        }
    };
    public static final Migration[] MIGRATIONS = new Migration[] {
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4
    };

    public abstract CodexLikesDao codexLikesDao();
    public abstract RecentsDao recentsDao();

    @NonNull
    public static TheoriaRoomDatabase create(@NonNull Context context) {
        return create(context, DEFAULT_DATABASE_NAME);
    }

    @NonNull
    public static TheoriaRoomDatabase create(
            @NonNull Context context,
            @NonNull String databaseName
    ) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        TheoriaRoomDatabase.class,
                        databaseName
                )
                .addMigrations(MIGRATIONS)
                .build();
    }
}
