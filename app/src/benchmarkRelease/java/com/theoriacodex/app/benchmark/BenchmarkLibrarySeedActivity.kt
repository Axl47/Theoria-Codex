package com.theoriacodex.app.benchmark

import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Restores a real library snapshot (a normal profile backup) into the freshly cleared benchmark app, so cold
 * starts measure a realistic library instead of an empty one. `scripts/verify_performance_device.sh` pushes the
 * snapshot to this app's media directory, launches this activity, and waits for [STATUS_FILE]. The restore goes
 * through the app's own backup path and selects the imported profile, like a user restoring a backup.
 */
class BenchmarkLibrarySeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = externalMediaDirs.first()
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = File(directory, SNAPSHOT_FILE)
                    val bytes = snapshot.readBytes().also { snapshot.delete() }
                    val owner = application as TheoriaAppContainerOwner
                    val backup = requireNotNull(owner.awaitAppContainer().workflows.profileBackup)
                    val result = backup.restore(bytes, applyPreferences = true, selectImportedProfile = true)
                    JSONObject().put("state", "complete")
                        .put("profilesAdded", result.profilesAdded).put("collectionsAdded", result.collectionsAdded)
                }.getOrElse { failure ->
                    JSONObject().put("state", "failed").put("error", failure.javaClass.simpleName)
                }
            }
            withContext(Dispatchers.IO) { writeStatus(File(directory, STATUS_FILE), status) }
            finish()
        }
    }

    private fun writeStatus(file: File, status: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        runCatching { output.write(status.toString().toByteArray()) }
            .onSuccess { atomic.finishWrite(output) }
            .onFailure { atomic.failWrite(output) }
    }

    private companion object {
        const val SNAPSHOT_FILE = "library-snapshot.json"
        const val STATUS_FILE = "library-seed-status.json"
    }
}
