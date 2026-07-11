package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomLegacyJsonImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: TheoriaRoomDatabase
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TheoriaRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = temporaryFolder.newFolder("legacy")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `imports both files once with checksum and count proof`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database, clock = { 900L })
        val repository = RoomCodexLikesRepository(database)

        val first = importer.importIfNeeded(directory)
        assertTrue(first is LegacyJsonImportResult.Imported)
        val proof = (first as LegacyJsonImportResult.Imported).proof
        assertEquals(1, proof.codexCount)
        assertEquals(1, proof.postCount)
        assertEquals(1, proof.itemCount)
        assertEquals(1, proof.likeCount)
        assertEquals(64, proof.sourceFingerprintSha256.length)
        assertEquals(64, proof.destinationFingerprintSha256.length)
        assertEquals(64, proof.proofSha256.length)
        assertEquals(900L, proof.completedAtEpochMs)
        assertEquals("Legacy", repository.observeCodex("legacy").first()?.name)
        assertEquals(
            listOf("42"),
            repository.observeCodexPosts("legacy", CodexSortMode.NEWEST_SAVED)
                .first()
                .map { post -> post.id.sourcePostId },
        )
        assertEquals(SourceKey.PIXIV, repository.observeLikes("profile-main").first().single().postId.source)

        val second = importer.importIfNeeded(directory)
        assertEquals(LegacyJsonImportResult.AlreadyImported(proof), second)

        val archive = importer.archiveImportedSources(directory)
        assertTrue(archive is LegacyArchiveResult.Complete)
        val archivedProof = (archive as LegacyArchiveResult.Complete).proof
        assertTrue(archivedProof.codexArchived)
        assertTrue(archivedProof.likesArchived)
        repository.createCodex("After migration")
        assertEquals(
            LegacyJsonImportResult.AlreadyImported(archivedProof),
            importer.importIfNeeded(directory),
        )
        assertEquals(LegacyArchiveResult.Complete(archivedProof), importer.archiveImportedSources(directory))
    }

    @Test
    fun `changed legacy source is reported and never replayed`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database)
        val repository = RoomCodexLikesRepository(database)
        importer.importIfNeeded(directory)

        writeLikesFile(validLikesJson().replace("liked", "changed"))
        val result = importer.importIfNeeded(directory)

        assertTrue(result is LegacyJsonImportResult.SplitBrain)
        assertEquals(listOf("liked", "Other"), repository.observeLikes("profile-main").first().single().tags)
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())
    }

    @Test
    fun `invalid second source rolls back the pair and retry imports after repair`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile("{")
        val importer = RoomLegacyJsonImporter(database)
        val repository = RoomCodexLikesRepository(database)

        val failed = runCatching { importer.importIfNeeded(directory) }
        assertTrue(failed.exceptionOrNull() is LegacyJsonMigrationException)
        assertTrue(repository.observeCodices().first().isEmpty())

        writeLikesFile(validLikesJson())
        assertTrue(importer.importIfNeeded(directory) is LegacyJsonImportResult.Imported)
        assertEquals(1, repository.observeCodices().first().size)
        assertEquals(1, repository.observeLikes("profile-main").first().size)
    }

    @Test
    fun `invalid legacy records fail closed without certifying or archiving partial data`() = runTest {
        val cases = listOf(
            InvalidLegacyCase(
                label = "null Codex",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonArray("codices").add(JsonNull.INSTANCE)
                },
            ),
            InvalidLegacyCase(
                label = "null Post",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonArray("posts").add(JsonNull.INSTANCE)
                },
            ),
            InvalidLegacyCase(
                label = "null Codex item",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonObject("items")
                        .getAsJsonArray("legacy")
                        .add(JsonNull.INSTANCE)
                },
            ),
            InvalidLegacyCase(
                label = "null Like",
                likesJson = mutateValidLikes { root ->
                    root.getAsJsonArray("likes").add(JsonNull.INSTANCE)
                },
            ),
            InvalidLegacyCase(
                label = "duplicate Codex id",
                codexJson = mutateValidCodex { root ->
                    val codices = root.getAsJsonArray("codices")
                    codices.add(codices[0].deepCopy())
                },
            ),
            InvalidLegacyCase(
                label = "duplicate Post id",
                codexJson = mutateValidCodex { root ->
                    val posts = root.getAsJsonArray("posts")
                    posts.add(posts[0].deepCopy())
                },
            ),
            InvalidLegacyCase(
                label = "duplicate Codex item key",
                codexJson = mutateValidCodex { root ->
                    val items = root.getAsJsonObject("items").getAsJsonArray("legacy")
                    items.add(items[0].deepCopy())
                },
            ),
            InvalidLegacyCase(
                label = "future Post schema",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonArray("posts")[0].asJsonObject.addProperty("schemaVersion", 99)
                },
            ),
            InvalidLegacyCase(
                label = "item map and row Codex mismatch",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonObject("items")
                        .getAsJsonArray("legacy")[0]
                        .asJsonObject
                        .addProperty("codexId", "different")
                },
            ),
            InvalidLegacyCase(
                label = "item group references unknown Codex",
                codexJson = mutateValidCodex { root ->
                    val items = root.getAsJsonObject("items")
                    items.add("missing", items.remove("legacy"))
                },
            ),
            InvalidLegacyCase(
                label = "item references missing Post",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonObject("items")
                        .getAsJsonArray("legacy")[0]
                        .asJsonObject
                        .addProperty("sourcePostId", "missing")
                },
            ),
            InvalidLegacyCase(
                label = "item has unknown source",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonObject("items")
                        .getAsJsonArray("legacy")[0]
                        .asJsonObject
                        .addProperty("source", "UNKNOWN")
                },
            ),
            InvalidLegacyCase(
                label = "Post has unknown source",
                codexJson = mutateValidCodex { root ->
                    root.getAsJsonArray("posts")[0]
                        .asJsonObject
                        .addProperty("source", "UNKNOWN")
                },
            ),
            InvalidLegacyCase(
                label = "duplicate Like key",
                likesJson = mutateValidLikes { root ->
                    val likes = root.getAsJsonArray("likes")
                    likes.add(likes[0].deepCopy())
                },
            ),
            InvalidLegacyCase(
                label = "Like has unknown source",
                likesJson = mutateValidLikes { root ->
                    root.getAsJsonArray("likes")[0]
                        .asJsonObject
                        .addProperty("source", "UNKNOWN")
                },
            ),
            InvalidLegacyCase(
                label = "Like has blank post id",
                likesJson = mutateValidLikes { root ->
                    root.getAsJsonArray("likes")[0]
                        .asJsonObject
                        .addProperty("sourcePostId", " ")
                },
            ),
        )
        val importer = RoomLegacyJsonImporter(database)
        val dao = database.codexLikesDao()

        cases.forEach { case ->
            writeCodexFile(case.codexJson)
            writeLikesFile(case.likesJson)

            val failed = runCatching { importer.importIfNeeded(directory) }

            assertTrue(case.label, failed.exceptionOrNull() is LegacyJsonMigrationException)
            assertTrue(
                "${case.label}: Codex source must remain retryable",
                directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists(),
            )
            assertTrue(
                "${case.label}: Likes source must remain retryable",
                directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists(),
            )
            assertEquals(
                "${case.label}: Codex source changed",
                case.codexJson,
                directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).readText(),
            )
            assertEquals(
                "${case.label}: Likes source changed",
                case.likesJson,
                directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).readText(),
            )
            assertTrue("${case.label}: no Codex rows", dao.codices().isEmpty())
            assertTrue("${case.label}: no Post rows", dao.posts().isEmpty())
            assertTrue("${case.label}: no membership rows", dao.codexItems().isEmpty())
            assertTrue("${case.label}: no Like rows", dao.allLikes().isEmpty())
            assertEquals(
                "${case.label}: no migration proof",
                null,
                dao.migrationMetadata(LEGACY_MIGRATION_KEY),
            )
            assertTrue(
                "${case.label}: archive must not be created",
                !directory.resolve(RoomLegacyJsonImporter.DEFAULT_ARCHIVE_DIRECTORY_NAME).exists(),
            )
        }
    }

    @Test
    fun `oversized legacy source fails before database mutation`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database, maxSourceBytes = 8L)
        val repository = RoomCodexLikesRepository(database)

        val failed = runCatching { importer.importIfNeeded(directory) }

        assertTrue(failed.exceptionOrNull() is LegacyJsonMigrationException)
        assertTrue(repository.observeCodices().first().isEmpty())
    }

    @Test
    fun `non-empty different destination is preserved and reported`() = runTest {
        val repository = RoomCodexLikesRepository(database, clock = { 77L }, newId = { "existing" })
        val existing = repository.createCodex("Existing")
        val existingPost = importerTestPost("existing-post")
        repository.addItem(existing.codexId, existingPost)
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())

        val result = RoomLegacyJsonImporter(database).importIfNeeded(directory)

        assertTrue(result is LegacyJsonImportResult.DestinationConflict)
        assertEquals(existingPost, repository.getPost(existingPost.id))
        assertEquals(null, repository.observeCodex("legacy").first())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())
    }

    @Test
    fun `tampered migration proof fails closed`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database)
        importer.importIfNeeded(directory)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_metadata SET proof_sha256 = '${"0".repeat(64)}'"
        )

        val result = importer.importIfNeeded(directory)

        assertTrue(result is LegacyJsonImportResult.InvalidStoredProof)
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())
    }

    @Test
    fun `destination drift blocks reuse and archival while preserving sources`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database)
        importer.importIfNeeded(directory)
        RoomCodexLikesRepository(database, newId = { "post-import" }).createCodex("Post import")

        val reused = importer.importIfNeeded(directory)
        val archived = importer.archiveImportedSources(directory)

        assertTrue(reused is LegacyJsonImportResult.DestinationDrift)
        assertTrue(archived is LegacyArchiveResult.Partial)
        assertEquals("Room destination", (archived as LegacyArchiveResult.Partial).blockedFile)
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())
    }

    @Test
    fun `partial archive is retryable without losing either source`() = runTest {
        val validLikes = validLikesJson()
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikes)
        val importer = RoomLegacyJsonImporter(database)
        importer.importIfNeeded(directory)
        writeLikesFile(validLikes.replace("liked", "changed"))

        val partial = importer.archiveImportedSources(directory)

        assertTrue(partial is LegacyArchiveResult.Partial)
        val partialProof = (partial as LegacyArchiveResult.Partial).proof
        assertTrue(partialProof.codexArchived)
        assertTrue(!partialProof.likesArchived)
        assertTrue(!directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).exists())
        assertTrue(directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())

        writeLikesFile(validLikes)
        val completed = importer.archiveImportedSources(directory)
        assertTrue(completed is LegacyArchiveResult.Complete)
        assertTrue((completed as LegacyArchiveResult.Complete).proof.likesArchived)
        assertTrue(!directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).exists())
    }

    @Test
    fun `archive move before proof update recovers on the next readiness attempt`() = runTest {
        writeCodexFile(validCodexJson())
        writeLikesFile(validLikesJson())
        val importer = RoomLegacyJsonImporter(database)
        val imported = importer.importIfNeeded(directory) as LegacyJsonImportResult.Imported
        val codexFile = directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME)
        val archiveDirectory = directory.resolve(RoomLegacyJsonImporter.DEFAULT_ARCHIVE_DIRECTORY_NAME)
        archiveDirectory.mkdirs()
        val codexArchive = archiveDirectory.resolve(
            "${codexFile.name}.${imported.proof.codexFileSha256.take(16)}.imported"
        )
        codexFile.copyTo(codexArchive)
        assertTrue(codexFile.delete())

        assertTrue(importer.importIfNeeded(directory) is LegacyJsonImportResult.AlreadyImported)
        val recovered = importer.archiveImportedSources(directory)

        assertTrue(recovered is LegacyArchiveResult.Complete)
        val recoveredProof = (recovered as LegacyArchiveResult.Complete).proof
        assertTrue(recoveredProof.codexArchived)
        assertTrue(recoveredProof.likesArchived)
    }

    private fun writeCodexFile(raw: String) {
        directory.resolve(RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME).writeText(raw)
    }

    private fun writeLikesFile(raw: String) {
        directory.resolve(RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME).writeText(raw)
    }
}

private data class InvalidLegacyCase(
    val label: String,
    val codexJson: String = validCodexJson(),
    val likesJson: String = validLikesJson(),
)

private fun mutateValidCodex(mutation: (com.google.gson.JsonObject) -> Unit): String {
    val root = JsonParser.parseString(validCodexJson()).asJsonObject
    mutation(root)
    return root.toString()
}

private fun mutateValidLikes(mutation: (com.google.gson.JsonObject) -> Unit): String {
    val root = JsonParser.parseString(validLikesJson()).asJsonObject
    mutation(root)
    return root.toString()
}

private fun validCodexJson(): String {
    return """
        {
          "codices": [
            {"codexId":"legacy","name":"Legacy","createdAtEpochMs":10}
          ],
          "items": {
            "legacy": [
              {"codexId":"legacy","source":"PIXIV","sourcePostId":"42","savedAtEpochMs":20}
            ]
          },
          "posts": [
            {
              "source":"PIXIV",
              "sourcePostId":"42",
              "previewUrl":"https://example.com/42.jpg",
              "previewLocalPath":null,
              "previewMime":"image/jpeg",
              "fullUrl":"https://example.com/full/42.jpg",
              "fullLocalPath":null,
              "fullMime":"image/jpeg",
              "pageUrl":"https://example.com/post/42",
              "width":100,
              "height":200,
              "canonicalTags":["legacy"],
              "rawTags":["legacy"],
              "authorName":"artist",
              "createdAtEpochMs":1
            }
          ]
        }
    """.trimIndent()
}

private fun validLikesJson(): String {
    return """
        {
          "likes": [
            {
              "profileId":"profile-main",
              "source":"PIXIV",
              "sourcePostId":"42",
              "likedAtEpochMs":30,
              "tags":["liked"," liked ","Other"]
            }
          ]
        }
    """.trimIndent()
}

private fun importerTestPost(id: String): Post {
    return Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = "https://example.com/post/$id",
        width = 100,
        height = 100,
        canonicalTags = listOf("existing"),
        rawTags = listOf("existing"),
        authorName = "artist",
        createdAtEpochMs = 1L,
    )
}
