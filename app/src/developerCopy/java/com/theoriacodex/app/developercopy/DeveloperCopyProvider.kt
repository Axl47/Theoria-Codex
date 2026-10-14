package com.theoriacodex.app.developercopy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.sourceauth.AndroidCredentialEnvelopeStore
import com.theoriacodex.app.sourceauth.CredentialEnvelopeReadResult
import com.theoriacodex.app.sourceauth.PREFS_NAME
import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.ProfileBackup
import com.theoriacodex.data.repository.ProfileBackupCodec
import com.theoriacodex.data.repository.ReadOnlyProfileBackupFiles
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Manifest entry exists only in opt-in personalRelease; every read authenticates Binder's UID. */
class DeveloperCopyProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exporting = AtomicBoolean(false)

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        require(app.packageName == PRODUCTION_PACKAGE && BuildConfig.DEVELOPER_COPY_ENABLED)
        val callers = app.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toList()
        val matches = runCatching {
            app.packageManager.requireCopySigner(DEBUG_PACKAGE, BuildConfig.DEVELOPER_COPY_CERT_SHA256)
        }.isSuccess
        requireCopyCaller(callers, matches)
        require(mode == "r" && uri == Uri.parse("content://$COPY_AUTHORITY/snapshot"))
        check(exporting.compareAndSet(false, true)) { "An export is already running" }
        val pipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (failure: Exception) {
            exporting.set(false)
            throw failure
        }
        scope.launch {
            var completed = false
            try {
                withTimeout(COPY_TIMEOUT_MS) {
                    val payload = snapshot()
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { writeCopyPayload(it, payload) }
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never expose exception messages: they can contain provider tokens or URLs.
            } finally {
                if (!completed) runCatching { pipe[1].closeWithError("Production snapshot unavailable; open the ordinary app and retry") }
                exporting.set(false)
            }
        }
        return pipe[0]
    }

    private suspend fun snapshot(): DeveloperCopyPayload {
        val app = requireNotNull(context)
        val files = ReadOnlyProfileBackupFiles.capture(File(app.filesDir, "theoria_codex"))
        val credentials = readCredentials()
        val staging = File(app.cacheDir, "developer-copy-${UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            val databaseFile = File(staging, "snapshot.db")
            stageReadOnlyLibrary(app.getDatabasePath(TheoriaRoomDatabase.DEFAULT_DATABASE_NAME), databaseFile)
            val database = TheoriaRoomDatabase.create(app, databaseFile.path)
            val library = try { RoomProfileBackupStore(database).snapshot(true) } finally { database.close() }
            files.requireUnchanged()
            require(credentials == readCredentials()) { "Accounts changed during export" }
            // Stable document identity makes retrying an unchanged snapshot idempotent in Debug.
            val backup = ProfileBackupCodec().encode(ProfileBackup(0, files.settings, library,
                files.savedSearches, files.readingPositions))
            return DeveloperCopyPayload(backup, credentials)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun readCredentials(): SourceCredentialSnapshot {
        val app = requireNotNull(context)
        return when (val read = AndroidCredentialEnvelopeStore(app, allowAtomicRecovery = false).read()) {
            is CredentialEnvelopeReadResult.Success -> read.snapshot
            CredentialEnvelopeReadResult.Missing -> {
                require(!File(app.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml").exists())
                SourceCredentialSnapshot()
            }
            else -> error("Open production normally to finish credential recovery before copying")
        }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
