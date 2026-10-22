package com.theoriacodex.app.developercopy

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.android.room.awaitProfileLibraryStores
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadOnlyLibrarySnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `actual Room library maps identically through the disposable staged database`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(temporary.newFolder(), "production.db")
        val stageFile = File(temporary.newFolder(), "snapshot.db")
        val source = TheoriaRoomDatabase.create(context, sourceFile.path)
        try {
            val storage = temporary.newFolder()
            awaitProfileLibraryStores(source, storage)
            val post = Post(id = PostId(SourceKey.PIXIV, "42"), preview = ImageRef(null, null, null), full = null,
                pageUrl = "https://www.pixiv.net/artworks/42", width = null, height = null,
                canonicalTags = listOf("tag"), rawTags = listOf("tag"), authorName = null, createdAtEpochMs = null)
            val store = RoomProfileBackupStore(source)
            store.merge(BackupLibrary(listOf(Codex("sample", "Sample", 0)),
                listOf(CodexItem("sample", post.id, 0)), listOf(post), emptyList()))
            val expected = store.snapshot(true)
            // A fresh Debug import must remain admissible to ordinary startup after copying.
            awaitProfileLibraryStores(source, storage)
            val before = sourceFile.readBytes()
            stageReadOnlyLibrary(sourceFile, stageFile)
            assertTrue(before.contentEquals(sourceFile.readBytes()))
            val staged = TheoriaRoomDatabase.create(context, stageFile.path)
            try { assertEquals(expected, RoomProfileBackupStore(staged).snapshot(true)) } finally { staged.close() }
        } finally {
            source.close()
        }
    }

    @Test fun `an external commit invalidates the snapshot and the read connection cannot write`() = runTest {
        val file = File(temporary.newFolder(), "production.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { writer ->
            writer.execSQL("CREATE TABLE posts (id INTEGER)")
            assertTrue(runCatching {
                withUnchangedReadOnlyLibrary(file) { reader ->
                    assertTrue(reader.isReadOnly)
                    assertTrue(runCatching { reader.execSQL("INSERT INTO posts VALUES (1)") }.isFailure)
                    writer.execSQL("INSERT INTO posts VALUES (2)")
                }
            }.isFailure)
            writer.rawQuery("SELECT id FROM posts", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
                assertEquals(1, it.count)
            }
        }
    }

    @Test fun `staging includes committed WAL rows without changing production database bytes`() = runTest {
        val sourceFile = File(temporary.newFolder(), "production.db")
        val stagedFile = File(temporary.newFolder(), "stage.db")
        SQLiteDatabase.openOrCreateDatabase(sourceFile, null).use { source ->
            source.enableWriteAheadLogging()
            source.execSQL("CREATE TABLE posts (source TEXT, source_post_id TEXT, payload_json TEXT, PRIMARY KEY(source, source_post_id))")
            source.execSQL("CREATE TABLE media_durations (ignored TEXT)")
            source.execSQL("CREATE INDEX post_identity ON posts(source)")
            source.execSQL("INSERT INTO posts VALUES ('PIXIV', '42', 'private metadata')")
            source.execSQL("INSERT INTO media_durations VALUES ('not portable')")
            source.version = 9
            val before = sourceFile.readBytes()
            val wal = File(sourceFile.path + "-wal")
            val walBefore = wal.takeIf { it.isFile }?.readBytes()
            stageReadOnlyLibrary(sourceFile, stagedFile)
            assertTrue(before.contentEquals(sourceFile.readBytes()))
            if (walBefore != null) assertTrue(walBefore.contentEquals(wal.readBytes()))
            SQLiteDatabase.openDatabase(stagedFile.path, null, SQLiteDatabase.OPEN_READONLY).use { stage ->
                assertEquals(9, stage.version)
                stage.rawQuery("SELECT source_post_id FROM posts", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("42", it.getString(0))
                }
                stage.rawQuery("SELECT count(*) FROM media_durations", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        }
    }
}
