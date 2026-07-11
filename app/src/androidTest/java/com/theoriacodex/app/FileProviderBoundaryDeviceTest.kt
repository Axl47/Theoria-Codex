package com.theoriacodex.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.update.APK_MIME_TYPE
import com.theoriacodex.app.update.buildApkInstallerIntent
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileProviderBoundaryDeviceTest {
    private val createdFiles = mutableListOf<File>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val authority: String
        get() = "${context.packageName}.fileprovider"

    @After
    fun removeBoundaryFixtures() {
        createdFiles.forEach { file -> file.delete() }
    }

    @Test
    fun updateApkUriSupportsInstallerReadGrantAndExactResolverRead() {
        val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x2A)
        val apkFile = writeFixture(
            relativePath = "theoria_codex/updates/${uniqueName("device-update")}.apk",
            bytes = apkBytes,
        )

        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installerIntent = buildApkInstallerIntent(apkUri)

        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(provider)
        assertEquals(context.packageName, provider?.packageName)
        assertFalse(provider?.exported ?: true)
        assertTrue(provider?.grantUriPermissions == true)
        assertEquals("content", apkUri.scheme)
        assertEquals(authority, apkUri.authority)
        assertEquals("update_apks", apkUri.pathSegments.first())
        assertEquals(Intent.ACTION_VIEW, installerIntent.action)
        assertEquals(apkUri, installerIntent.data)
        assertEquals(APK_MIME_TYPE, installerIntent.type)
        assertEquals(apkUri, installerIntent.clipData?.getItemAt(0)?.uri)
        assertTrue(installerIntent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertTrue(installerIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(
            installerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertArrayEquals(apkBytes, readContent(apkUri))
    }

    @Test
    fun codexExportShareCarriesStreamClipAndReadableExactBytes() {
        val exportBytes = """{"title":"Device Boundary","posts":[]}"""
            .toByteArray(Charsets.UTF_8)
        val exportFile = writeFixture(
            relativePath = "theoria_codex/exports/${uniqueName("codex-export")}.json",
            bytes = exportBytes,
        )
        val exportUri = FileProvider.getUriForFile(context, authority, exportFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, exportUri)
            clipData = ClipData.newRawUri("codex_export", exportUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        @Suppress("DEPRECATION")
        val streamUri = shareIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals("application/json", shareIntent.type)
        assertEquals(exportUri, streamUri)
        assertEquals(exportUri, shareIntent.clipData?.getItemAt(0)?.uri)
        assertEquals("codex_exports", exportUri.pathSegments.first())
        assertTrue(
            shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertArrayEquals(exportBytes, readContent(exportUri))
    }

    @Test
    fun codexImportIntakeReadsARealContentUriThroughContentResolver() {
        val importBytes = """{"title":"Imported On Device","posts":[]}"""
            .toByteArray(Charsets.UTF_8)
        val importFile = writeFixture(
            relativePath = "theoria_codex/exports/${uniqueName("codex-import-intake")}.json",
            bytes = importBytes,
        )
        val incomingUri = FileProvider.getUriForFile(context, authority, importFile)
        val importIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(incomingUri, "application/json")
            clipData = ClipData.newRawUri("codex_import", incomingUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        assertEquals("content", importIntent.data?.scheme)
        assertEquals("application/json", importIntent.type)
        assertEquals(incomingUri, importIntent.clipData?.getItemAt(0)?.uri)
        assertTrue(
            importIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertArrayEquals(importBytes, readContent(requireNotNull(importIntent.data)))
    }

    @Test
    fun fileProviderRejectsFilesOutsideDeclaredUpdateAndExportRoots() {
        val privateFile = writeFixture(
            relativePath = "theoria_codex/private/${uniqueName("not-shared")}.json",
            bytes = byteArrayOf(1, 2, 3),
        )

        val failure = runCatching {
            FileProvider.getUriForFile(context, authority, privateFile)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun writeFixture(relativePath: String, bytes: ByteArray): File {
        val file = File(context.filesDir, relativePath)
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) {
            "Could not create device-test fixture directory for $relativePath"
        }
        file.writeBytes(bytes)
        createdFiles += file
        return file
    }

    private fun readContent(uri: Uri): ByteArray {
        return requireNotNull(context.contentResolver.openInputStream(uri)) {
            "ContentResolver could not open $uri"
        }.use { input -> input.readBytes() }
    }

    private fun uniqueName(prefix: String): String {
        return "$prefix-${UUID.randomUUID()}"
    }
}
