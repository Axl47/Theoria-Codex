package com.theoriacodex.data.android.room

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private typealias Row = Map<String, Any?>

object RoomMigrationScenarios {
    const val SPIKE = "custom_mihon_7"
    const val TWO_DIGIT_SPIKE = "custom_mihon_12"
    const val NAMED_SITE = "custom_mihon_example_org"
    const val DIGIT_SITE = "custom_mihon_9anime_org"
    private const val SITE = "custom_mihon_example_org"

    val steps: Map<Int, (MigrationTestHelper) -> Unit> = mapOf(
        10 to ::proofTablesGo,
        11 to ::forYouHistoryGoes,
        12 to ::verdictsAndSignaturesArrive,
        13 to ::spikeRowsGo,
        14 to ::downloadFlagArrivesOff,
        15 to ::hashesStayTried,
        16 to ::analysesAwaitFrames,
        17 to ::sharingArrivesOff,
        18 to ::followTimesFromFirstSeen,
        19 to ::memosArriveEmpty,
        20 to ::checkTimeGoes,
        21 to ::translationCacheGoes,
        22 to ::checkTimeReturnsEmpty,
    )

    fun proofTablesGo(helper: MigrationTestHelper) {
        for (withProofRows in listOf(true, false)) {
            val name = "migration-10-$withProofRows.db"
            helper.createDatabase(name, 10).use {
                it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
                if (withProofRows) {
                    it.execSQL("INSERT INTO migration_metadata VALUES('codex-likes','s','c','l','d','p',1,1,1,1,1,1,1,1,1)")
                    it.execSQL("INSERT INTO recents_migration_metadata VALUES('recents','s',1,1,0,'d',1,1,'p',1,1)")
                }
            }
            helper.runMigrationsAndValidate(name, 11, true, migration(10)).use { database ->
                assertEquals(listOf("Saved"), database.strings("SELECT name FROM codices WHERE codex_id='saved'"))
                assertEquals(emptyList<String>(), database.strings("SELECT name FROM sqlite_master WHERE name IN ('migration_metadata','recents_migration_metadata')"))
            }
        }
    }

    fun forYouHistoryGoes(helper: MigrationTestHelper) {
        val name = "migration-11.db"
        val fyp = """{"recentSearchSchemaVersion":1,"query":{"schemaVersion":2,"modeType":"unified","includeTags":["x"],""" +
            """"excludeTags":[],"sort":"TOP"},"kind":"FYP","sources":["PIXIV"],"sourceTags":{"PIXIV":["x"]}}"""
        val unified = fyp.replace("FYP", "UNIFIED").replace(""""sourceTags":{"PIXIV":["x"]}""", """"sourceTags":{}""")
        helper.createDatabase(name, 11).use {
            it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
            for (section in listOf("FYP", "WATCHED")) {
                it.execSQL(
                    "INSERT INTO recent_watched(source,source_post_id,section,viewed_at_epoch_ms,sort_sequence,origin) VALUES('PIXIV','1',?,1,1,'SEARCH')",
                    arrayOf(section),
                )
            }
            it.execSQL("INSERT INTO recent_searches VALUES('for_you:seed',?,1,1)", arrayOf(fyp))
            it.execSQL("INSERT INTO recent_searches VALUES('unified',?,2,2)", arrayOf(unified))
        }
        helper.runMigrationsAndValidate(name, 12, true, migration(11)).use { database ->
            assertEquals(listOf("WATCHED"), database.strings("SELECT section FROM recent_watched"))
            assertEquals(listOf("unified"), database.strings("SELECT query_hash FROM recent_searches"))
        }
    }

    fun verdictsAndSignaturesArrive(helper: MigrationTestHelper) {
        val name = "migration-12.db"
        helper.createDatabase(name, 12).use {
            it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','1','{}')")
        }
        helper.runMigrationsAndValidate(name, 13, true, migration(12)).use { database ->
            database.execSQL(
                "INSERT INTO for_you_verdicts(profile_id,source,source_post_id,decision,decided_at_epoch_ms,preview_url) VALUES('default','PIXIV','2','SKIPPED',1,NULL)",
            )
            database.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('PIXIV','1',94,1)")
            database.execSQL("PRAGMA foreign_keys=ON")
            database.execSQL("DELETE FROM posts WHERE source='PIXIV' AND source_post_id='1'")
            assertEquals(listOf("0"), database.strings("SELECT COUNT(*) FROM post_visual_signatures"))
            assertEquals(listOf("SKIPPED"), database.strings("SELECT decision FROM for_you_verdicts"))
        }
    }

    fun spikeRowsGo(helper: MigrationTestHelper) {
        val name = "migration-13.db"
        val tables = listOf("codex_items", "liked_posts", "recent_watched", "media_durations", "codex_automatic_tags", "post_visual_signatures", "posts")
        val sources = listOf(SPIKE, TWO_DIGIT_SPIKE, NAMED_SITE, DIGIT_SITE)
        helper.createDatabase(name, 13).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            for (source in sources) {
                it.execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('$source','1','{}')")
                it.execSQL("INSERT INTO codex_items VALUES('saved','$source','1',1)")
                it.execSQL("INSERT INTO liked_posts VALUES('a','$source','1',1,'[]')")
                it.execSQL(
                    "INSERT INTO recent_watched(source,source_post_id,viewed_at_epoch_ms,sort_sequence,origin,section,max_viewed_media_number) " +
                        "VALUES('$source','1',1,1,'SEARCH','WATCHED',1)",
                )
                it.execSQL(
                    "INSERT INTO media_durations(source,source_post_id,media_fingerprint,decision,updated_at_epoch_ms) VALUES('$source','1','full-media-v1','KNOWN',1)",
                )
                it.execSQL("INSERT INTO codex_automatic_tags(codex_id,source,tag_key,tag_display,group_index) VALUES('saved','$source','sky','sky',0)")
                it.execSQL("INSERT INTO post_visual_signatures(source,source_post_id,phash,indexed_at_epoch_ms) VALUES('$source','1',94,1)")
                it.execSQL("INSERT INTO for_you_verdicts(profile_id,source,source_post_id,decision,decided_at_epoch_ms) VALUES('a','$source','1','SKIPPED',1)")
            }
        }
        helper.runMigrationsAndValidate(name, 14, true, migration(13)).use { database ->
            for (table in tables) assertEquals(table, listOf(DIGIT_SITE, NAMED_SITE), database.strings("SELECT source FROM `$table` ORDER BY source"))
            assertEquals(sources.sorted(), database.strings("SELECT source FROM for_you_verdicts ORDER BY source"))
        }
    }

    fun spikeSearchesGo(helper: MigrationTestHelper) {
        val name = "migration-13-searches.db"
        val searches = mapOf(
            "top" to """{"modeSource":"$SPIKE","includeTags":["sky"]}""",
            "nested" to """{"query":{"modeSource":"$SPIKE"},"sources":["PIXIV"]}""",
            "sources" to """{"query":{"modeSource":"PIXIV"},"sources":["PIXIV","$SPIKE"]}""",
            "source-tags" to """{"query":{"modeSource":"PIXIV"},"sourceTags":{"$SPIKE":["sky"]}}""",
            "two-digit" to """{"query":{"modeSource":"$TWO_DIGIT_SPIKE"}}""",
            "tag-only" to """{"query":{"modeSource":"PIXIV","includeTags":["$SPIKE"]}}""",
            "unreadable" to """{"modeSource":"$SPIKE"""",
            "named-site" to """{"query":{"modeSource":"$NAMED_SITE"},"sources":["$DIGIT_SITE"],"sourceTags":{"$DIGIT_SITE":[]}}""",
        )
        helper.createDatabase(name, 13).use {
            searches.entries.forEachIndexed { index, (hash, payload) ->
                it.execSQL("INSERT INTO recent_searches VALUES(?,?,?,?)", arrayOf<Any>(hash, payload, index, index))
            }
        }
        helper.runMigrationsAndValidate(name, 14, true, migration(13)).use { database ->
            assertEquals(listOf("named-site", "tag-only", "unreadable"), database.strings("SELECT query_hash FROM recent_searches ORDER BY query_hash"))
        }
    }

    fun downloadFlagArrivesOff(helper: MigrationTestHelper) = step(helper, 14, SERIES_TABLES, { seedSeries() }) { table, row ->
        if (table == "series") row + ("download_new" to 0L) else row
    }

    fun hashesStayTried(helper: MigrationTestHelper) = step(helper, 15, listOf("post_visual_signatures"), { seedSignatures() }) { _, row ->
        row + mapOf("phash_checked" to 1L, "model_id" to null, "embedding" to null, "predicted_tags" to null)
    }

    fun analysesAwaitFrames(helper: MigrationTestHelper) = step(helper, 16, listOf("post_visual_signatures"), { seedSignatures() }) { _, row ->
        row + mapOf("frame_state" to if (row["model_id"] == null) 0L else 1L, "frame_attempts" to 0L)
    }

    fun sharingArrivesOff(helper: MigrationTestHelper) = step(helper, 17, SERIES_TABLES, { seedSeries() }) { table, row ->
        if (table == "series") row + ("chapters_share_first_page" to 0L) else row
    }

    fun followTimesFromFirstSeen(helper: MigrationTestHelper) = step(helper, 18, SERIES_TABLES, { seedSeries() }) { table, row ->
        when (table) {
            "series" -> row + ("followed_at_epoch_ms" to FIRST_SEEN[row["address"]].takeIf { row["followed"] == 1L })
            else -> row + ("last_read_at_epoch_ms" to null)
        }
    }

    fun memosArriveEmpty(helper: MigrationTestHelper) = step(helper, 19, SERIES_TABLES, { seedSeries() }) { _, row ->
        row + ("memo" to null)
    }

    fun checkTimeGoes(helper: MigrationTestHelper) = step(helper, 20, SERIES_TABLES, { seedSeries() }) { _, row ->
        row - "last_checked_at_epoch_ms"
    }

    fun translationCacheGoes(helper: MigrationTestHelper) {
        val name = "migration-21.db"
        helper.createDatabase(name, 21).use {
            it.execSQL("INSERT INTO codices(codex_id,name,created_at_epoch_ms,display_order) VALUES('saved','Saved',1,0)")
            it.execSQL(
                "INSERT INTO viewer_translation_cache(backend_version,source_language,source_text_sha256,translated_text,expires_at_epoch_ms,last_used_at_epoch_ms) " +
                    "VALUES('google-nmt-v1','JAPANESE','hash','English',100,1)",
            )
        }
        helper.runMigrationsAndValidate(name, 22, true, migration(21)).use { database ->
            assertEquals(listOf("Saved"), database.strings("SELECT name FROM codices"))
            assertEquals(emptyList<String>(), database.strings("SELECT name FROM sqlite_master WHERE name LIKE '%viewer_translation_cache%'"))
        }
    }

    fun checkTimeReturnsEmpty(helper: MigrationTestHelper) = step(helper, 22, SERIES_TABLES, { seedSeries() }) { table, row ->
        if (table == "series") row + ("checked_at_epoch_ms" to null) else row
    }

    private fun step(
        helper: MigrationTestHelper,
        from: Int,
        tables: List<String>,
        seed: SupportSQLiteDatabase.() -> Unit,
        expected: (table: String, row: Row) -> Row?,
    ) {
        val name = "migration-$from.db"
        val before = helper.createDatabase(name, from).use { database ->
            database.seed()
            tables.associateWith { database.rows(it) }
        }
        for (table in tables) assertTrue(table, before.getValue(table).isNotEmpty())
        helper.runMigrationsAndValidate(name, from + 1, true, migration(from)).use { database ->
            for (table in tables) assertEquals(table, before.getValue(table).mapNotNull { expected(table, it) }.sortedBy(::key), database.rows(table))
        }
    }

    private fun migration(from: Int): Migration = TheoriaRoomDatabase.MIGRATIONS.single { it.startVersion == from }

    private val SERIES_TABLES = listOf("series", "series_entries")

    private val FIRST_SEEN = mapOf("/followed" to 10L)

    private fun SupportSQLiteDatabase.seedSeries() {
        insert("series", series("/followed", followed = 1, "VERTICAL", opened = "/followed/2", checked = 50, download = 1, share = 1, followedAt = 5, memo = """{"s":1}"""))
        insert("series", series("/unfollowed", followed = 0, "PAGED", opened = "/unfollowed/1", checked = 60, download = 1, share = 1, followedAt = null, memo = """{"s":2}"""))
        insert("series", series("/empty", followed = 1, null, opened = null, checked = null, download = 0, share = 0, followedAt = 7, memo = null))
        insert("series_entries", entry("/followed", "/followed/1", order = 2, firstSeen = 30, read = 1, page = 9, pages = 9, readAt = 70, memo = """{"e":1}"""))
        insert("series_entries", entry("/followed", "/followed/2", order = 1, firstSeen = 20, read = 0, page = 4, pages = 9, readAt = 80, memo = null))
        insert("series_entries", entry("/followed", "/followed/3", order = 0, firstSeen = 40, read = 0, page = 0, pages = null, readAt = null, memo = null))
        insert("series_entries", entry("/followed", "/followed/old", order = -1, firstSeen = 10, read = 1, page = 3, pages = 3, readAt = 60, memo = null))
        insert("series_entries", entry("/unfollowed", "/unfollowed/1", order = 0, firstSeen = 15, read = 1, page = 5, pages = 5, readAt = 90, memo = """{"e":2}"""))
        insert("series_entries", entry("/unfollowed", "/unfollowed/2", order = 1, firstSeen = 25, read = 0, page = 2, pages = 8, readAt = null, memo = null))
        insert("series_entries", entry("/unfollowed", "/unfollowed/old", order = -1, firstSeen = 5, read = 0, page = 0, pages = null, readAt = null, memo = null))
    }

    private fun series(address: String, followed: Int, mode: String?, opened: String?, checked: Long?, download: Int, share: Int, followedAt: Long?, memo: String?): Row = mapOf(
        "source" to SITE, "address" to address, "title" to address.drop(1), "cover_url" to "$address.jpg".takeIf { followed == 1 },
        "author" to "Author", "genres" to """["x"]""", "language" to "en", "followed" to followed, "reading_mode" to mode,
        "last_opened_entry" to opened, "last_checked_at_epoch_ms" to checked, "description" to "About", "status" to "ONGOING",
        "download_new" to download, "chapters_share_first_page" to share, "followed_at_epoch_ms" to followedAt, "memo" to memo,
    )

    private fun entry(series: String, address: String, order: Int, firstSeen: Long, read: Int, page: Int, pages: Int?, readAt: Long?, memo: String?): Row = mapOf(
        "source" to SITE, "series_address" to series, "address" to address, "name" to address.substringAfterLast('/'),
        "number" to order + 0.5, "translator_group" to "group".takeIf { read == 1 }, "uploaded_at_epoch_ms" to firstSeen * 2,
        "source_order" to order, "first_seen_at_epoch_ms" to firstSeen, "read" to read, "last_page" to page, "page_count" to pages,
        "last_read_at_epoch_ms" to readAt, "memo" to memo,
    )

    private fun SupportSQLiteDatabase.seedSignatures() {
        for (id in 1..5) execSQL("INSERT INTO posts(source,source_post_id,payload_json) VALUES('PIXIV','$id','{}')")
        val rows = listOf(
            signature("1", phash = 94, checked = 1, model = "m1", embedding = byteArrayOf(1, 2), tags = "solo"),
            signature("2", phash = null, checked = 1, model = "m1", embedding = null, tags = null),
            signature("3", phash = 95, checked = 1, model = null, embedding = null, tags = null),
            signature("4", phash = null, checked = 0, model = null, embedding = null, tags = null),
            signature("5", phash = null, checked = 0, model = "m1", embedding = byteArrayOf(3), tags = ""),
        )
        val tried = columns("post_visual_signatures").contains("phash_checked")
        for (row in rows) if (tried || row["phash_checked"] == 1) insert("post_visual_signatures", row)
    }

    private fun signature(id: String, phash: Long?, checked: Int, model: String?, embedding: ByteArray?, tags: String?): Row = mapOf(
        "source" to "PIXIV", "source_post_id" to id, "phash" to phash, "phash_checked" to checked, "indexed_at_epoch_ms" to id.toLong(),
        "model_id" to model, "embedding" to embedding, "predicted_tags" to tags,
    )

    private fun SupportSQLiteDatabase.insert(table: String, row: Row) {
        val values = row.filterKeys(columns(table)::contains)
        execSQL(
            "INSERT INTO `$table` (${values.keys.joinToString { "`$it`" }}) VALUES (${values.keys.joinToString { "?" }})",
            values.values.toTypedArray(),
        )
    }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { buildSet { while (it.moveToNext()) add(it.getString(1)) } }

    private fun SupportSQLiteDatabase.rows(table: String): List<Row> = query("SELECT * FROM `$table`").use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.value(it) }) }
    }.sortedBy(::key)

    private fun Cursor.value(column: Int): Any? = when (getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> getBlob(column).toList()
        else -> getString(column)
    }

    private fun key(row: Row): String = row.toSortedMap().toString()

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
}
