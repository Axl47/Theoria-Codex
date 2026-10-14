package com.theoriacodex.app.developercopy

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.widget.TextView
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.sourceauth.AndroidCredentialEnvelopeStore
import com.theoriacodex.app.sourceauth.CredentialStoreWriteResult
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.android.room.awaitProfileLibraryStores
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.FileBackedSavedSearchRepository
import com.theoriacodex.data.repository.ProfileBackupService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** ADB launches this only after placing a one-shot request in Debug's private storage using run-as. */
class DeveloperCopyActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName != DEBUG_PACKAGE || !BuildConfig.DEBUG) {
            finish()
            return
        }
        val job = intent.getStringExtra("job")
        if (job == null || !job.matches(Regex("[0-9a-f-]{36}"))) {
            finish()
            return
        }
        setContentView(TextView(this).apply { text = "Copying production profiles into Debug…"; setPadding(32, 32, 32, 32) })
        scope.launch {
            withContext(Dispatchers.IO) {
                val request = consumeRequest(job) ?: return@withContext
                writeStatus(job, "running")
                var completed = false
                try {
                    withTimeout(COPY_TIMEOUT_MS) {
                        packageManager.requireCopySigner(PRODUCTION_PACKAGE, request)
                        val provider = packageManager.resolveContentProvider(COPY_AUTHORITY, 0)
                        require(provider?.packageName == PRODUCTION_PACKAGE && provider.exported)
                        val input = requireNotNull(contentResolver.openInputStream(Uri.parse("content://$COPY_AUTHORITY/snapshot")))
                        val payload = input.use(::readCopyPayload)
                        importCopy(payload)
                    }
                    writeStatus(job, "complete")
                    completed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Publish only the non-secret outcome below.
                } finally {
                    if (!completed) writeStatus(job, "failed")
                }
            }
            finish()
        }
    }

    private fun consumeRequest(job: String): String? = runCatching {
        val file = File(noBackupFilesDir, "$COPY_DIRECTORY/request.json")
        require(file.length() in 1..4096)
        val request = JsonParser.parseString(file.readText()).asJsonObject
        require(request.get("job").asString == job)
        val issuedAt = request.get("issuedAtEpochMs").asLong
        require(System.currentTimeMillis() - issuedAt in 0..60_000)
        val signer = request.get("productionCertificateSha256").asString
        require(signer.matches(Regex("[0-9a-f]{64}")))
        check(file.delete())
        signer
    }.getOrNull()

    private suspend fun importCopy(payload: DeveloperCopyPayload) {
        val storage = File(filesDir, "theoria_codex")
        val storesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = TheoriaRoomDatabase.create(this)
        try {
            val settings = DataStoreSettingsRepository(storage, storesScope)
            settings.awaitReady()
            awaitProfileLibraryStores(database, storage)
            val backup = ProfileBackupService(
                settings, RoomProfileBackupStore(database), FileBackedSavedSearchRepository(storage),
                FileBackedReadingPositionRepository(storage), File(storage, "pending_profile_restore.json"),
                newOperationId = { "copy-${sha256(payload.backup)}" },
            )
            // Recover any earlier admitted restore before starting a new snapshot's import.
            backup.recoverPendingRestore()
            backup.restore(payload.backup, applyPreferences = true, selectImportedProfile = true)
            check(AndroidCredentialEnvelopeStore(this).writeVerified(payload.credentials) == CredentialStoreWriteResult.Success)
        } finally {
            storesScope.cancel()
            database.close()
        }
    }

    private fun writeStatus(job: String, state: String) {
        val status = AtomicFile(File(noBackupFilesDir, "$COPY_DIRECTORY/status.json"))
        val output = status.startWrite()
        try {
            val bytes = JsonObject().apply {
                addProperty("job", job)
                addProperty("state", state)
            }.toString().toByteArray(Charsets.UTF_8)
            output.write(bytes)
            status.finishWrite(output)
        } catch (failure: Exception) {
            status.failWrite(output)
            throw failure
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
