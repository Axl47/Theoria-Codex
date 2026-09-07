package com.theoriacodex.app.acceptance

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.theoriacodex.app.codex.CodexShareFile
import com.theoriacodex.app.codex.CodexSharePost
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installed only in the minified acceptance APK; write/read phases straddle a process restart. */
class ReleaseDataAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token").orEmpty()
        val phase = intent.getStringExtra("phase").orEmpty()
        require(token.matches(Regex("[a-f0-9]{24}")) && phase in setOf("write", "read"))
        val status = TextView(this).also { setContentView(it) }
        status.text = "Checking minified data contracts"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { verifyData(File(cacheDir, "release-contract-$token"), phase) }
                status.text = "Minified data contracts passed: $phase"
                Log.i(RESULT_TAG, "PASS:$token:$phase")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status.text = "Minified data contracts failed"
                Log.e(RESULT_TAG, "FAIL:$token:$phase:${failure.javaClass.simpleName}")
            }
        }
    }
}

private suspend fun verifyData(directory: File, phase: String) {
    val gson = GsonBuilder().serializeNulls().create()
    val updateFile = File(directory, "update.json")
    val shareFile = File(directory, "collection.json")
    val postFile = File(directory, "post.json")
    val tagsFile = File(directory, "tags.json")
    if (phase == "write") {
        check(directory.mkdirs())
        FileBackedUpdateStateStore(updateFile).apply {
            setIgnoredRelease(RELEASE_ID)
            setRemindLater(RELEASE_ID, REMIND_UNTIL)
        }
        shareFile.writeText(gson.toJson(CodexShareFile(
            title = "Acceptance collection",
            posts = listOf(CodexSharePost(source = "PIXIV", sourcePostId = "42")),
        )))
        postFile.writeText("""{"source":"PIXIV","sourcePostId":"42","schemaVersion":1}""")
        tagsFile.writeText("""{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""")
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            check(tags.get(SourceKey.GELBOORU, 10).any { it.text == "legacy" })
            tags.put(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "seen", 10)))
            tags.flush()
        } finally {
            tags.close()
        }
    } else {
        val snapshot = FileBackedUpdateStateStore(updateFile).snapshot()
        check(snapshot.ignoredReleaseId == RELEASE_ID)
        check(snapshot.remindLaterReleaseId == RELEASE_ID && snapshot.remindLaterUntilEpochMs == REMIND_UNTIL)
        val wire = JsonParser.parseString(shareFile.readText()).asJsonObject
        check(wire.keySet() == setOf("version", "title", "posts"))
        val share = gson.fromJson(shareFile.readText(), CodexShareFile::class.java)
        check(share.title == "Acceptance collection" && share.posts?.single()?.sourcePostId == "42")
        val post = gson.fromJson(postFile.readText(), PostStorageRecord::class.java)
        check(post.source == "PIXIV" && post.sourcePostId == "42")
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            check(tags.get(SourceKey.GELBOORU, 10).any { it.text == "fresh" })
        } finally {
            tags.close()
        }
        check(directory.deleteRecursively())
    }
}

private const val RESULT_TAG = "TheoriaReleaseData"
private const val RELEASE_ID = 42L
private const val REMIND_UNTIL = 123_456L
