package com.theoriacodex.app.vision

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One file of a picture model, pinned by size and SHA-256 so only the exact published bytes are ever loaded. */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/**
 * A downloadable picture model: [id] names it in stored analyses, so a new model (a new id) re-analyses everything.
 * Files are fetched from [baseUrl] + their name.
 */
data class VisualModelSpec(val id: String, val baseUrl: String, val model: ModelFile, val vocabulary: ModelFile) {
    val files: List<ModelFile> get() = listOf(model, vocabulary)
    val totalBytes: Long get() = files.sumOf(ModelFile::bytes)

    companion object {
        /** WD ViT tagger v3 (SmilingWolf, Apache-2.0), exported with both outputs and dynamic-range int8 weights. */
        fun wdVitTaggerV3(baseUrl: String) = VisualModelSpec(
            id = "wd-vit-tagger-v3-int8@1",
            baseUrl = baseUrl,
            model = ModelFile("wd-vit-tagger-v3.int8.tflite", 97_988_336L, "7cab93650b2203f0fc537c88b1115017122e5939156cb4a717321cdf570283aa"),
            vocabulary = ModelFile("wd-vit-tagger-v3.selected_tags.csv", 308_468L, "298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217"),
        )
    }
}

/** Where the model's download stands. */
sealed interface ModelState {
    data object Absent : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Ready : ModelState

    /** The last download stopped; [mismatch] when its bytes were not the pinned ones (rather than a network failure). */
    data class Failed(val mismatch: Boolean) : ModelState
}

/**
 * The opt-in model's files under [directory] (the app's no-backup storage). A file is renamed into place only after its
 * size and SHA-256 match [spec], so a file present is a verified file. [delete] stops a download, removes the files and
 * runs [onDeleted] (which forgets what the model predicted); nothing else in the app changes while the model is absent.
 */
class VisualModelStore(
    private val spec: VisualModelSpec,
    root: File,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onDeleted: suspend () -> Unit,
) {
    private val directory = File(root, spec.id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private val state = MutableStateFlow(if (installed()) ModelState.Ready else ModelState.Absent)
    private val lock = Mutex()
    private var download: Job? = null

    val id: String get() = spec.id
    val totalBytes: Long get() = spec.totalBytes
    val states: StateFlow<ModelState> = state.asStateFlow()

    fun file(file: ModelFile): File = File(directory, file.name)

    val modelFile: File get() = file(spec.model)
    val vocabularyFile: File get() = file(spec.vocabulary)

    /** Starts the download unless it runs or the model is ready. */
    fun download() {
        scope.launch {
            lock.withLock {
                if (state.value is ModelState.Downloading || state.value == ModelState.Ready) return@withLock
                state.value = ModelState.Downloading(0, spec.totalBytes)
                download = scope.launch(Dispatchers.IO) { fetchAll() }
            }
        }
    }

    /** Stops a download, removes the model and forgets what it predicted. */
    suspend fun delete() {
        lock.withLock {
            download?.cancel()
            download?.join()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
            state.value = ModelState.Absent
        }
        onDeleted()
    }

    private suspend fun fetchAll() {
        val outcome = runCatchingPreservingCancellation {
            directory.mkdirs()
            var before = 0L
            for (file in spec.files) {
                if (!file(file).isFile) fetch(file) { done -> state.value = ModelState.Downloading(before + done, spec.totalBytes) }
                before += file.bytes
            }
        }
        state.value = outcome.fold(
            onSuccess = { ModelState.Ready },
            onFailure = { error ->
                Diagnostics.report("vision", "The picture tagger download failed", error)
                ModelState.Failed(mismatch = error is MismatchException)
            },
        )
    }

    private suspend fun fetch(file: ModelFile, progress: (Long) -> Unit) {
        val part = File(directory, "${file.name}.part")
        try {
            val request = Request.Builder().url(spec.baseUrl + file.name).header("Accept-Encoding", "identity").build()
            val call = client.newCall(request)
            // A blocking read cancelled from outside: cancelling the call makes it throw at once.
            val cancelOnStop = coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response -> receive(response, file, part, progress) }
            } finally {
                cancelOnStop.dispose()
            }
            if (!part.renameTo(file(file))) throw IOException("could not move ${file.name} into place")
        } finally {
            part.delete()
        }
    }

    /** Streams [response] into [part], hashing as it goes; throws unless the bytes are exactly [file]'s. */
    private suspend fun receive(response: okhttp3.Response, file: ModelFile, part: File, progress: (Long) -> Unit) {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${file.name}")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        response.body.byteStream().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    done += read
                    if (done > file.bytes) throw MismatchException("${file.name} is larger than ${file.bytes} bytes")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    progress(done)
                }
                output.fd.sync()
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (done != file.bytes || sha != file.sha256) throw MismatchException("${file.name}: $done bytes, sha256 $sha")
    }

    private fun installed(): Boolean = spec.files.all { file(it).length() == it.bytes }

    private class MismatchException(message: String) : IOException(message)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
