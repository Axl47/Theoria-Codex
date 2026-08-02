package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.LegacyRecentPostRecord
import com.theoriacodex.data.storage.LegacyRecentSearchRecord
import com.theoriacodex.data.storage.LegacyRecentsStoreFile
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.QueryStorageCodec
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomRecentsLegacyImporterTest {
    private lateinit var database: TheoriaRoomDatabase
    private lateinit var directory: File
    private lateinit var registry: LegacyJsonRecoveryRegistry

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
        directory = Files.createTempDirectory("recents-import-").toFile()
        registry = LegacyJsonRecoveryRegistry()
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test fun `valid source normalizes dedupes archives and imports before observation`() = runTest {
        val older = importerPost("same").copy(title = "older")
        val newer = older.copy(title = "newer")
        writeLegacy(
            watched = listOf(
                LegacyRecentPostRecord(PostStorageCodec.encode(newer), 2L, "CODEX", "new"),
                null,
                LegacyRecentPostRecord(PostStorageCodec.encode(older), 2L, "SEARCH", "old"),
            ),
            searches = listOf(
                LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("old")), " hash ", 1L),
                LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("new")), "hash", 2L),
                LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("blank")), " ", 3L),
            ),
        )
        val importer = RoomRecentsLegacyImporter(database, registry, clock = { 50L })

        val result = importer.importAndArchive(directory)

        assertTrue(result is RecentsImportResult.AlreadyImported)
        val repository = RoomRecentsRepository(database)
        assertEquals("newer", repository.observeWatchedPosts().first().single().post.title)
        assertEquals("new", repository.observeWatchedPosts().first().single().originQueryHash)
        assertEquals(listOf("new"), repository.observeSearches().first().single().query.includeTags)
        assertFalse(directory.resolve(LEGACY_RECENTS_FILE_NAME).exists())
        assertEquals(1, directory.resolve("legacy-json-archive").listFiles().orEmpty().size)
    }

    @Test fun `completed migration permits live Recents and shared Post evolution on restart`() = runTest {
        writeLegacy(
            watched = listOf(LegacyRecentPostRecord(PostStorageCodec.encode(importerPost("one")), 1L, "SEARCH", null)),
        )
        val importer = RoomRecentsLegacyImporter(database, registry)
        assertTrue(importer.importAndArchive(directory) is RecentsImportResult.AlreadyImported)
        val repository = RoomRecentsRepository(database, clock = { 10L })
        repository.recordWatchedPost(importerPost("one").copy(title = "refreshed"), ViewerStreamSource.RECENTS, null)
        repository.recordSearch(importerQuery("live"), "live")

        val restarted = RoomRecentsLegacyImporter(database, LegacyJsonRecoveryRegistry())
        assertTrue(restarted.importAndArchive(directory) is RecentsImportResult.AlreadyImported)
        assertEquals("refreshed", repository.observeWatchedPosts().first().single().post.title)
    }

    @Test fun `legacy partial snapshot preserves rich shared payload while applying update`() = runTest {
        val codex = RoomCodexLikesRepository(database, clock = { 1L }, newId = { "codex" })
        val rich = importerPost("shared-rich").copy(
            preview = ImageRef(
                "https://example.com/preview.jpg",
                "/cached/preview.jpg",
                "image/jpeg",
                progressiveUrls = listOf("https://example.com/progressive.jpg"),
            ),
            full = ImageRef("https://example.com/full.jpg", null, "image/jpeg"),
            media = listOf(
                ImageRef("https://example.com/page-1.jpg", null, "image/jpeg"),
                ImageRef("https://example.com/page-2.jpg", null, "image/jpeg"),
            ),
            canonicalTags = listOf("rich", "shared"),
            rawTags = listOf("rich_raw", "shared_raw"),
            title = "original title",
            durationMs = 1_000L,
        )
        codex.addItem(codex.createCodex("Saved").codexId, rich)
        val partial = importerPost("shared-rich").copy(
            preview = ImageRef(null, null, null),
            full = null,
            media = emptyList(),
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            title = "legacy update",
            durationMs = 2_000L,
        )
        writeLegacy(
            watched = listOf(
                LegacyRecentPostRecord(PostStorageCodec.encode(partial), 2L, "SEARCH", null)
            ),
        )

        assertTrue(
            RoomRecentsLegacyImporter(database, registry).importAndArchive(directory) is
                RecentsImportResult.AlreadyImported
        )

        val stored = requireNotNull(codex.getPost(rich.id))
        assertEquals(rich.preview, stored.preview)
        assertEquals(rich.full, stored.full)
        assertEquals(rich.media, stored.media)
        assertEquals(rich.canonicalTags, stored.canonicalTags)
        assertEquals(rich.rawTags, stored.rawTags)
        assertEquals("legacy update", stored.title)
        assertEquals(2_000L, stored.durationMs)
    }

    @Test fun `archive-created source-deleted crash resumes the pending archive proof`() = runTest {
        val source = writeLegacy(
            watched = listOf(LegacyRecentPostRecord(PostStorageCodec.encode(importerPost("one")), 1L, "SEARCH", null)),
        )
        val bytes = source.readBytes()
        val importer = RoomRecentsLegacyImporter(database, registry)
        val imported = importer.importRowsIfNeeded(source) as RecentsImportResult.Imported
        val archive = directory.resolve("legacy-json-archive")
            .resolve("${source.name}.${bytes.size}-${imported.proof.sourceSha256}.imported")
        requireNotNull(archive.parentFile).mkdirs()
        archive.writeBytes(bytes)
        assertTrue(source.delete())

        val resumed = RoomRecentsLegacyImporter(database, LegacyJsonRecoveryRegistry())
            .importAndArchive(directory)

        assertTrue(resumed is RecentsImportResult.AlreadyImported)
        assertTrue((resumed as RecentsImportResult.AlreadyImported).proof.sourceArchived)
        assertTrue(archive.readBytes().contentEquals(bytes))
    }

    @Test fun `unarchived destination drift fails closed`() = runTest {
        val source = writeLegacy(
            watched = listOf(LegacyRecentPostRecord(PostStorageCodec.encode(importerPost("one")), 1L, "SEARCH", null)),
        )
        val importer = RoomRecentsLegacyImporter(database, registry)
        assertTrue(importer.importRowsIfNeeded(source) is RecentsImportResult.Imported)
        RoomRecentsRepository(database).clearWatchedPosts()

        assertTrue(importer.importRowsIfNeeded(source) is RecentsImportResult.DestinationDrift)
        assertTrue(source.exists())
    }

    @Test fun `source change after an unarchived proof fails closed`() = runTest {
        val source = writeLegacy(
            searches = listOf(LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("one")), "one", 1L)),
        )
        val importer = RoomRecentsLegacyImporter(database, registry)
        assertTrue(importer.importRowsIfNeeded(source) is RecentsImportResult.Imported)
        source.writeText(Gson().toJson(LegacyRecentsStoreFile(
            searches = listOf(LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("two")), "two", 2L))
        )))

        assertTrue(importer.importRowsIfNeeded(source) is RecentsImportResult.SourceChanged)
        assertEquals(listOf("one"), RoomRecentsRepository(database).observeSearches().first().map { it.queryHash })
    }

    @Test fun `invalid stored proof fails closed before touching the source`() = runTest {
        val source = writeLegacy()
        database.recentsDao().insertMigration(
            RecentsMigrationEntity(
                RECENTS_MIGRATION_KEY,
                "not-a-hash",
                0L,
                false,
                false,
                "also-invalid",
                0,
                0,
                "bad-proof",
                0L,
                true,
            )
        )

        assertTrue(
            RoomRecentsLegacyImporter(database, registry).importAndArchive(directory) is
                RecentsImportResult.InvalidProof
        )
        assertTrue(source.exists())
    }

    @Test fun `archive collision leaves source and pending proof intact`() = runTest {
        val source = writeLegacy(
            searches = listOf(LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("one")), "one", 1L)),
        )
        val importer = RoomRecentsLegacyImporter(database, registry)
        val imported = importer.importRowsIfNeeded(source) as RecentsImportResult.Imported
        val archive = directory.resolve("legacy-json-archive")
            .resolve("${source.name}.${imported.proof.sourceByteCount}-${imported.proof.sourceSha256}.imported")
        requireNotNull(archive.parentFile).mkdirs()
        archive.writeText("different")

        assertTrue(importer.importAndArchive(directory) is RecentsImportResult.ArchiveFailed)
        assertTrue(source.exists())
        assertFalse(imported.proof.sourceArchived)
    }

    @Test fun `archive filesystem failure leaves source and pending proof intact`() = runTest {
        val source = writeLegacy(
            searches = listOf(LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("one")), "one", 1L)),
        )
        val importer = RoomRecentsLegacyImporter(database, registry)
        val imported = importer.importRowsIfNeeded(source) as RecentsImportResult.Imported
        directory.resolve("legacy-json-archive").writeText("blocks archive directory")

        assertTrue(importer.importAndArchive(directory) is RecentsImportResult.ArchiveFailed)
        assertTrue(source.exists())
        assertFalse(imported.proof.sourceArchived)
    }

    @Test fun `failed import transaction leaves neither rows posts nor proof and can retry`() = runTest {
        val source = writeLegacy(
            watched = listOf(LegacyRecentPostRecord(PostStorageCodec.encode(importerPost("one")), 1L, "SEARCH", null)),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_import BEFORE INSERT ON recent_watched BEGIN SELECT RAISE(ABORT, 'no'); END"
        )
        val importer = RoomRecentsLegacyImporter(database, registry)

        assertTrue(runCatching { importer.importRowsIfNeeded(source) }.isFailure)
        assertTrue(database.recentsDao().watched().isEmpty())
        assertTrue(database.codexLikesDao().posts().isEmpty())
        assertEquals(null, database.recentsDao().migration(RECENTS_MIGRATION_KEY))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_import")
        assertTrue(importer.importAndArchive(directory) is RecentsImportResult.AlreadyImported)
        assertEquals(1, database.recentsDao().watched().size)
    }

    @Test fun `all unreadable whole-file forms quarantine exact bytes and prove zero rows`() = runTest {
        val corruptions = listOf(
            "malformed" to "{broken".encodeToByteArray(),
            "empty" to byteArrayOf(),
            "null" to "null".encodeToByteArray(),
            "invalid-utf8" to byteArrayOf(0xC3.toByte(), 0x28),
        )
        corruptions.forEach { (name, bytes) ->
            val caseDatabase = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                TheoriaRoomDatabase::class.java,
            ).allowMainThreadQueries().build()
            val caseDirectory = Files.createTempDirectory("recents-corrupt-$name-").toFile()
            try {
                val caseRegistry = LegacyJsonRecoveryRegistry()
                caseDirectory.resolve(LEGACY_RECENTS_FILE_NAME).writeBytes(bytes)
                val result = RoomRecentsLegacyImporter(caseDatabase, caseRegistry)
                    .importAndArchive(caseDirectory)
                val proof = when (result) {
                    is RecentsImportResult.Imported -> result.proof
                    is RecentsImportResult.AlreadyImported -> result.proof
                    else -> error("$name unexpectedly returned $result")
                }
                assertTrue("$name should be quarantined", proof.sourceQuarantined)
                assertTrue(proof.sourceArchived)
                assertEquals(0, proof.watchedCount)
                assertEquals(0, proof.searchCount)
                assertTrue(File(caseRegistry.recoveries.value.single().backupPath!!)
                    .readBytes().contentEquals(bytes))
            } finally {
                caseDatabase.close()
                caseDirectory.deleteRecursively()
            }
        }
    }

    @Test fun `marker-free destination conflict is never merged`() = runTest {
        RoomRecentsRepository(database).recordSearch(importerQuery("existing"), "existing")
        writeLegacy(searches = listOf(
            LegacyRecentSearchRecord(QueryStorageCodec.encode(importerQuery("legacy")), "legacy", 1L)
        ))

        assertTrue(
            RoomRecentsLegacyImporter(database, registry).importAndArchive(directory) is
                RecentsImportResult.DestinationConflict
        )
        assertEquals(listOf("existing"), RoomRecentsRepository(database).observeSearches().first().map { it.queryHash })
        assertTrue(directory.resolve(LEGACY_RECENTS_FILE_NAME).exists())
    }

    private fun writeLegacy(
        watched: List<LegacyRecentPostRecord?> = emptyList(),
        searches: List<LegacyRecentSearchRecord?> = emptyList(),
    ): File = directory.resolve(LEGACY_RECENTS_FILE_NAME).also { file ->
        file.writeText(Gson().toJson(LegacyRecentsStoreFile(watched, searches)))
    }
}

private fun importerQuery(tag: String) = Query(
    mode = QueryMode.Source(SourceKey.PIXIV),
    includeTerms = listOf(SearchTerm(tag)),
    excludeTerms = emptyList(),
    sort = SortMode.TOP,
    dateRange = null,
    minScore = null,
)

private fun importerPost(id: String) = Post(
    id = PostId(SourceKey.PIXIV, id),
    preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
    full = null,
    pageUrl = "https://example.com/$id",
    width = 100,
    height = 100,
    canonicalTags = listOf("tag"),
    rawTags = listOf("tag"),
    authorName = "artist",
    createdAtEpochMs = 1L,
)
