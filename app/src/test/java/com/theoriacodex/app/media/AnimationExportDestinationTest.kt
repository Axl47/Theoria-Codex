package com.theoriacodex.app.media

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AnimationExportDestinationTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: ExportProvider
    private val marker = UUID.randomUUID().toString()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = ExportProvider(temp.root)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun `saved export opens through its tracked content URI and missing file uses post fallback`() = runTest {
        val destination = AnimationExportDestination(context)
        val uri = destination.save("local.mp4", marker) { write(it, "complete") }
        val item = PostDownloadItem(2, PostDownloadRequest(testPost(), marker = marker), PostDownloadStatus.SAVED)
        val files = PostDownloadFiles(context)
        val local = requireNotNull(files.find(item))
        assertEquals(uri, local.uri)
        assertEquals("video/mp4", local.mime)
        var fileOpens = 0
        var postOpens = 0
        openPostDownload(item, { files.find(it) }, { fileOpens++; true }, { postOpens++ })
        assertEquals(1, fileOpens)
        assertEquals(0, postOpens)
        openPostDownload(item, { files.find(it) }, { false }, { postOpens++ })
        assertEquals(1, postOpens)
        provider.file(uri).delete()
        openPostDownload(item, { files.find(it) }, { error("Missing file must not open") }, { postOpens++ })
        assertEquals(2, postOpens)
        destination.deleteCompleted(marker)
        assertNull(destination.findCompleted(marker))
    }

    @Test fun `cancelled file lookup never opens a replacement post`() = runTest {
        val item = PostDownloadItem(2, PostDownloadRequest(testPost()))
        val result = runCatching {
            openPostDownload(item, { throw kotlinx.coroutines.CancellationException() },
                { error("Cancelled") }, { error("Cancelled") })
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun `explicit export deletion removes only its tracked destination and is safe to repeat`() {
        val destination = AnimationExportDestination(context)
        val first = destination.save("delete.mp4", marker) { write(it, "first") }
        val otherMarker = UUID.randomUUID().toString()
        val other = destination.save("keep.mp4", otherMarker) { write(it, "other") }
        destination.deleteCompleted(marker)
        assertNull(destination.findCompleted(marker))
        assertTrue(!provider.file(first).exists())
        assertEquals(other, destination.findCompleted(otherMarker))
        destination.deleteCompleted(marker)
    }

    @Test fun `explicit private export deletion removes the marker-bound file`() {
        provider.failInsert = true
        val destination = AnimationExportDestination(context)
        val uri = destination.save("private.mp4", marker) { write(it, "private") }
        destination.deleteCompleted(marker)
        assertTrue(!File(requireNotNull(uri.path)).exists())
    }

    @Test fun `publication acknowledgement loss and reopening never encode or insert twice`() {
        var encodes = 0
        provider.losePublishAcknowledgement = true
        val first = AnimationExportDestination(context).save("animation.mp4", marker) { encodes++; write(it, "complete") }
        val reopened = AnimationExportDestination(context)
        assertEquals(first, reopened.findCompleted(marker))
        assertEquals(first, reopened.save("renamed.mp4", marker) { error("Must reuse the completed public file") })
        assertEquals(1, provider.inserts)
        assertEquals(1, encodes)
        assertEquals("complete", provider.file(first).readText())
    }

    @Test fun `interrupted publication resumes the same pending row and truncates incomplete bytes`() {
        val first = AnimationExportDestination(context)
        try {
            first.save("animation.mp4", marker) { write(it, "partial bytes that must be replaced"); throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) { }
        assertNull(first.findCompleted(marker))
        val uri = AnimationExportDestination(context).save("animation.mp4", marker) { write(it, "done") }
        assertEquals(1, provider.inserts)
        assertEquals("done", provider.file(uri).readText())
        assertEquals(uri, first.findCompleted(marker))
        assertTrue(provider.includedPending)
    }

    @Test @Config(sdk = [28])
    fun `legacy MediaStore rows require completion metadata rather than nonempty partial bytes`() {
        val destination = AnimationExportDestination(context)
        try {
            destination.save("legacy.mp4", marker) { write(it, "partial"); throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) { }
        assertNull(destination.findCompleted(marker))
        val completed = destination.save("legacy.mp4", marker) { write(it, "complete") }
        assertEquals(completed, destination.findCompleted(marker))
        assertEquals(1, provider.inserts)
    }

    @Test fun `private fallback is atomically completed and reconciled by marker`() {
        provider.failInsert = true
        val destination = AnimationExportDestination(context)
        val uri = destination.save("fallback.mp4", marker) { write(it, "private complete") }
        try {
            assertEquals("file", uri.scheme)
            assertEquals(uri, AnimationExportDestination(context).findCompleted(marker))
            assertEquals(uri, destination.save("another title.mp4", marker) { error("Must reuse the private file") })
            assertEquals("private complete", File(requireNotNull(uri.path)).readText())
        } finally { File(requireNotNull(uri.path)).delete() }
    }

    @Test fun `completed exports reconcile before settings provider lookup or another export`() = runTest {
        AnimationExportDestination(context).save("complete.mp4", marker) { write(it, "complete") }
        val backend = AndroidPostDownloadBackend(context,
            readSettings = { error("Must reconcile locally first") }, resolvePost = { error("No provider request") },
            exportAnimation = { _, _ -> error("No second encode") })
        val post = testPost().copy(id = PostId(SourceKey.PIXIV, "123"))
        assertEquals(PreparedPostDownload.Saved, backend.prepare(PostDownloadRequest(post, marker = marker, restored = true)))
    }

    @Test fun `legacy ambiguous preparation refuses to repeat an animation export`() = runTest {
        val media = ImageRef(null, null, PIXIV_UGOIRA_MIME)
        val post = testPost().copy(id = PostId(SourceKey.PIXIV, "123"), full = media, media = listOf(media))
        val backend = AndroidPostDownloadBackend(context, readSettings = { com.theoriacodex.data.repository.CacheSettings() },
            resolvePost = { post }, exportAnimation = { _, _ -> error("Untracked legacy export must not repeat") })
        assertTrue(runCatching { backend.prepare(PostDownloadRequest(post, restored = true, untrackedPreparation = true)) }
            .exceptionOrNull() is DownloadPreparationException)
    }

    private fun write(fd: FileDescriptor, text: String) {
        FileOutputStream(fd).apply { write(text.toByteArray()); flush() }
    }

    private class SimulatedProcessDeath : Error()

    private class ExportProvider(private val root: File) : ContentProvider() {
        private val rows = linkedMapOf<Long, ContentValues>()
        var inserts = 0
        var failInsert = false
        var losePublishAcknowledgement = false
        var includedPending = false
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?): Cursor {
            val include = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE
            includedPending = includedPending || include
            return rowsCursor(projection, queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), include)
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (failInsert) throw IOException("MediaStore unavailable")
            val id = (++inserts).toLong()
            rows[id] = ContentValues(requireNotNull(values))
            return ContentUris.withAppendedId(uri, id)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            rowsCursor(projection, selectionArgs, Build.VERSION.SDK_INT < 29 || uri.getQueryParameter("includePending") == "1")
        private fun rowsCursor(projection: Array<out String>?, arguments: Array<out String>?, include: Boolean): Cursor =
            MatrixCursor(requireNotNull(projection)).apply {
                rows.filterValues { it.getAsString(MediaStore.Video.VideoColumns.DESCRIPTION) == arguments?.single() &&
                    (include || it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) != 1) }
                    .keys.forEach { addRow(arrayOf(it)) }
            }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.getValue(ContentUris.parseId(uri)).putAll(requireNotNull(values))
            if (losePublishAcknowledgement) {
                losePublishAcknowledgement = false
                throw IOException("Published before caller acknowledgement")
            }
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.remove(ContentUris.parseId(uri))
            file(uri).delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        fun file(uri: Uri) = root.resolve("${ContentUris.parseId(uri)}.mp4")
    }
}
