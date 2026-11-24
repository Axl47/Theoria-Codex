package com.theoriacodex.app.benchmark

import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BenchmarkLibrarySeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = externalMediaDirs.first()
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatchingPreservingCancellation {
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
