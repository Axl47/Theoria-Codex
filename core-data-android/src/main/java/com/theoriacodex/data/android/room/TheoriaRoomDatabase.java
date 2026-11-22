package com.theoriacodex.data.android.room;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@Database(
        entities = {
                CodexEntity.class,
                CodexAutomaticTagEntity.class,
                PostEntity.class,
                CodexItemEntity.class,
                LikedPostEntity.class,
                RecentWatchedEntity.class,
                RecentSearchEntity.class,
                MediaDurationEntity.class,
                ViewerTranslationCacheEntity.class,
                ForYouVerdictEntity.class,
                VisualSignatureEntity.class,
                SeriesEntity.class,
                SeriesEntryEntity.class
        },
        version = 14,
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
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `media_durations` (`source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `media_fingerprint` TEXT NOT NULL, `decision` TEXT NOT NULL, `duration_ms` INTEGER, `provenance` TEXT, `reason` TEXT, `retry_at_epoch_ms` INTEGER, `updated_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`source`, `source_post_id`, `media_fingerprint`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_media_durations_updated_at_epoch_ms_source_source_post_id_media_fingerprint` ON `media_durations` (`updated_at_epoch_ms`, `source`, `source_post_id`, `media_fingerprint`)");
        }
    };
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `recent_watched` ADD COLUMN `max_viewed_media_number` INTEGER NOT NULL DEFAULT 1");
        }
    };
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Existing source-specific rules were OR expressions, so they all remain in group 0.
            database.execSQL("ALTER TABLE `codex_automatic_tags` ADD COLUMN `group_index` INTEGER NOT NULL DEFAULT 0");
        }
    };
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `viewer_translation_cache` (`backend_version` TEXT NOT NULL, `source_language` TEXT NOT NULL, `source_text_sha256` TEXT NOT NULL, `translated_text` TEXT NOT NULL, `expires_at_epoch_ms` INTEGER NOT NULL, `last_used_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`backend_version`, `source_language`, `source_text_sha256`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_viewer_translation_cache_last_used_at_epoch_ms_backend_version_source_language_source_text_sha256` ON `viewer_translation_cache` (`last_used_at_epoch_ms`, `backend_version`, `source_language`, `source_text_sha256`)");
        }
    };
    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_posts_source_source_post_id` ON `liked_posts` (`source`, `source_post_id`)");
        }
    };
    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS `index_codex_items_codex_id`");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_codex_items_codex_id_saved_at_epoch_ms` ON `codex_items` (`codex_id`, `saved_at_epoch_ms`)");
        }
    };
    // Drops the proof tables of the retired JSON importers; nothing has read them since.
    public static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS `migration_metadata`");
            database.execSQL("DROP TABLE IF EXISTS `recents_migration_metadata`");
        }
    };
    // The For You shelves page is gone: drop its search history and the posts some builds briefly kept from it.
    public static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DELETE FROM `recent_searches` WHERE `query_payload_json` LIKE '%\"kind\":\"FYP\"%'");
            database.execSQL("DELETE FROM `recent_watched` WHERE `section` = 'FYP'");
        }
    };
    // Logs For You swipes per profile and keeps each stored post's perceptual hash.
    public static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `for_you_verdicts` (`profile_id` TEXT NOT NULL, `source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `decision` TEXT NOT NULL, `decided_at_epoch_ms` INTEGER NOT NULL, `preview_url` TEXT, PRIMARY KEY(`profile_id`, `source`, `source_post_id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_for_you_verdicts_profile_id_decided_at_epoch_ms` ON `for_you_verdicts` (`profile_id`, `decided_at_epoch_ms`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `post_visual_signatures` (`source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `phash` INTEGER, `indexed_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`source`, `source_post_id`), FOREIGN KEY(`source`, `source_post_id`) REFERENCES `posts`(`source`, `source_post_id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        }
    };
    // Series from extensions get tables of their own. The spike that showed a series as a post stored those posts
    // under `custom_mihon_` source ids, which no source answers for any more: they are deleted from every table here.
    // Preferences are not touched.
    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `series` (`source` TEXT NOT NULL, `address` TEXT NOT NULL, `title` TEXT NOT NULL, `cover_url` TEXT, `author` TEXT, `genres` TEXT NOT NULL, `language` TEXT NOT NULL, `followed` INTEGER NOT NULL, `reading_mode` TEXT, `last_opened_entry` TEXT, `last_checked_at_epoch_ms` INTEGER, `description` TEXT, `status` TEXT, PRIMARY KEY(`source`, `address`))");
            database.execSQL("CREATE TABLE IF NOT EXISTS `series_entries` (`source` TEXT NOT NULL, `series_address` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `number` REAL, `translator_group` TEXT, `uploaded_at_epoch_ms` INTEGER, `source_order` INTEGER NOT NULL, `first_seen_at_epoch_ms` INTEGER NOT NULL, `read` INTEGER NOT NULL, `last_page` INTEGER NOT NULL, `page_count` INTEGER, PRIMARY KEY(`source`, `series_address`, `address`), FOREIGN KEY(`source`, `series_address`) REFERENCES `series`(`source`, `address`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            // Rows that point at a post go before the post itself.
            for (String table : new String[] {"codex_items", "liked_posts", "recent_watched", "media_durations", "codex_automatic_tags", "post_visual_signatures", "posts"}) {
                database.execSQL("DELETE FROM `" + table + "` WHERE " + SPIKE_SOURCE);
            }
            deleteSpikeSearches(database);
        }
    };
    // A spike id is `custom_mihon_` and digits only: a Custom Site can be named `custom_mihon_example_org`.
    private static final String SPIKE_SOURCE = "`source` GLOB 'custom_mihon_[0-9]*' AND `source` NOT GLOB 'custom_mihon_*[^0-9]*'";
    private static final Pattern SPIKE_SOURCE_ID = Pattern.compile("custom_mihon_[0-9]+");

    /** Deletes the recent searches that name a spike source as a source; the same text in a tag is left alone. */
    private static void deleteSpikeSearches(SupportSQLiteDatabase database) {
        List<String> spikes = new ArrayList<>();
        try (Cursor rows = database.query("SELECT `query_hash`, `query_payload_json` FROM `recent_searches` "
                + "WHERE `query_payload_json` LIKE '%custom\\_mihon\\_%' ESCAPE '\\'")) {
            while (rows.moveToNext()) {
                if (namesSpikeSource(rows.getString(1))) spikes.add(rows.getString(0));
            }
        }
        for (String hash : spikes) {
            database.execSQL("DELETE FROM `recent_searches` WHERE `query_hash` = ?", new Object[] {hash});
        }
    }

    /** Whether a recent search's payload has a spike id in a source field. Old payloads are the query alone. */
    private static boolean namesSpikeSource(String payloadJson) {
        try {
            JSONObject payload = new JSONObject(payloadJson);
            JSONObject query = payload.optJSONObject("query");
            List<String> sources = new ArrayList<>();
            sources.add(payload.optString("modeSource"));
            if (query != null) sources.add(query.optString("modeSource"));
            JSONArray searched = payload.optJSONArray("sources");
            for (int index = 0; searched != null && index < searched.length(); index++) sources.add(searched.optString(index));
            JSONObject sourceTags = payload.optJSONObject("sourceTags");
            if (sourceTags != null) sourceTags.keys().forEachRemaining(sources::add);
            for (String source : sources) {
                if (SPIKE_SOURCE_ID.matcher(source).matches()) return true;
            }
            return false;
        } catch (JSONException unreadable) {
            return false;
        }
    }
    public static final Migration[] MIGRATIONS = new Migration[] {
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14
    };

    public abstract CodexLikesDao codexLikesDao();
    public abstract RecentsDao recentsDao();
    public abstract MediaDurationDao mediaDurationDao();
    public abstract ViewerTranslationCacheDao viewerTranslationCacheDao();
    public abstract ForYouVerdictDao forYouVerdictDao();
    public abstract VisualSignatureDao visualSignatureDao();
    public abstract SeriesDao seriesDao();

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
