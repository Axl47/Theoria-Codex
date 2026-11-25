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
                ForYouVerdictEntity.class,
                VisualSignatureEntity.class,
                SeriesEntity.class,
                SeriesEntryEntity.class
        },
        version = 23,
        exportSchema = true
)
public abstract class TheoriaRoomDatabase extends RoomDatabase {
    public static final String DEFAULT_DATABASE_NAME = "theoria_content.db";

    public static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS `migration_metadata`");
            database.execSQL("DROP TABLE IF EXISTS `recents_migration_metadata`");
        }
    };
    public static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DELETE FROM `recent_searches` WHERE `query_payload_json` LIKE '%\"kind\":\"FYP\"%'");
            database.execSQL("DELETE FROM `recent_watched` WHERE `section` = 'FYP'");
        }
    };
    public static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `for_you_verdicts` (`profile_id` TEXT NOT NULL, `source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `decision` TEXT NOT NULL, `decided_at_epoch_ms` INTEGER NOT NULL, `preview_url` TEXT, PRIMARY KEY(`profile_id`, `source`, `source_post_id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_for_you_verdicts_profile_id_decided_at_epoch_ms` ON `for_you_verdicts` (`profile_id`, `decided_at_epoch_ms`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `post_visual_signatures` (`source` TEXT NOT NULL, `source_post_id` TEXT NOT NULL, `phash` INTEGER, `indexed_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`source`, `source_post_id`), FOREIGN KEY(`source`, `source_post_id`) REFERENCES `posts`(`source`, `source_post_id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        }
    };
    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `series` (`source` TEXT NOT NULL, `address` TEXT NOT NULL, `title` TEXT NOT NULL, `cover_url` TEXT, `author` TEXT, `genres` TEXT NOT NULL, `language` TEXT NOT NULL, `followed` INTEGER NOT NULL, `reading_mode` TEXT, `last_opened_entry` TEXT, `last_checked_at_epoch_ms` INTEGER, `description` TEXT, `status` TEXT, PRIMARY KEY(`source`, `address`))");
            database.execSQL("CREATE TABLE IF NOT EXISTS `series_entries` (`source` TEXT NOT NULL, `series_address` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `number` REAL, `translator_group` TEXT, `uploaded_at_epoch_ms` INTEGER, `source_order` INTEGER NOT NULL, `first_seen_at_epoch_ms` INTEGER NOT NULL, `read` INTEGER NOT NULL, `last_page` INTEGER NOT NULL, `page_count` INTEGER, PRIMARY KEY(`source`, `series_address`, `address`), FOREIGN KEY(`source`, `series_address`) REFERENCES `series`(`source`, `address`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            for (String table : new String[] {"codex_items", "liked_posts", "recent_watched", "media_durations", "codex_automatic_tags", "post_visual_signatures", "posts"}) {
                database.execSQL("DELETE FROM `" + table + "` WHERE " + SPIKE_SOURCE);
            }
            deleteSpikeSearches(database);
        }
    };
    public static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` ADD COLUMN `download_new` INTEGER NOT NULL DEFAULT 0");
        }
    };
    private static final String SPIKE_SOURCE = "`source` GLOB 'custom_mihon_[0-9]*' AND `source` NOT GLOB 'custom_mihon_*[^0-9]*'";
    private static final Pattern SPIKE_SOURCE_ID = Pattern.compile("custom_mihon_[0-9]+");

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
    public static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `phash_checked` INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `model_id` TEXT");
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `embedding` BLOB");
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `predicted_tags` TEXT");
        }
    };
    public static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `frame_state` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `post_visual_signatures` ADD COLUMN `frame_attempts` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("UPDATE `post_visual_signatures` SET `frame_state` = 1 WHERE `model_id` IS NOT NULL");
        }
    };
    public static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` ADD COLUMN `chapters_share_first_page` INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` ADD COLUMN `followed_at_epoch_ms` INTEGER");
            database.execSQL("ALTER TABLE `series_entries` ADD COLUMN `last_read_at_epoch_ms` INTEGER");
            database.execSQL("UPDATE `series` SET `followed_at_epoch_ms` = (SELECT MIN(`first_seen_at_epoch_ms`) FROM `series_entries` "
                    + "WHERE `series_entries`.`source` = `series`.`source` AND `series_entries`.`series_address` = `series`.`address`) "
                    + "WHERE `followed` = 1");
        }
    };

    public static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` ADD COLUMN `memo` TEXT");
            database.execSQL("ALTER TABLE `series_entries` ADD COLUMN `memo` TEXT");
        }
    };

    public static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` DROP COLUMN `last_checked_at_epoch_ms`");
        }
    };
    public static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS `viewer_translation_cache`");
        }
    };

    public static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `series` ADD COLUMN `checked_at_epoch_ms` INTEGER");
        }
    };

    public static final Migration[] MIGRATIONS = new Migration[] {
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23
    };

    public abstract CodexLikesDao codexLikesDao();
    public abstract RecentsDao recentsDao();
    public abstract MediaDurationDao mediaDurationDao();
    public abstract ForYouVerdictDao forYouVerdictDao();
    abstract VisualSignatureDao visualSignatureDao();
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
