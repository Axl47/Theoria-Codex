package com.theoriacodex.app.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.theoriacodex.app.codex.CodexShareFile
import com.theoriacodex.app.codex.CodexSharePost
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.UpdateStateSnapshot
import com.theoriacodex.data.storage.LegacyImportProof
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseJsonCompatibilityDeviceTest {
    private val gson = GsonBuilder().serializeNulls().create()

    @Test
    fun stableWireKeysSurviveTheInstalledTargetApk() {
        assertJsonKeys(
            LegacyImportProof(),
            "sourceFileName",
            "sourceSchemaVersion",
            "destinationSchemaVersion",
            "sourceSha256",
            "sourceByteCount",
            "importedCounts",
        )
        assertJsonKeys(
            PostStorageRecord(),
            "source",
            "sourcePostId",
            "previewUrl",
            "previewLocalPath",
            "previewMime",
            "previewProgressiveUrls",
            "previewIsAnimated",
            "fullUrl",
            "fullLocalPath",
            "fullMime",
            "fullProgressiveUrls",
            "fullIsAnimated",
            "pageUrl",
            "width",
            "height",
            "canonicalTags",
            "rawTags",
            "authorName",
            "createdAtEpochMs",
            "media",
            "title",
            "creatorProfile",
            "durationMs",
            "mediaCount",
            "taxonomy",
            "creatorProfiles",
            "schemaVersion",
        )
        assertJsonKeys(
            UpdateStateSnapshot(
                pendingPostInstallChangelog = PendingPostInstallChangelog(
                    releaseId = 7L,
                    versionCode = 8,
                    tagName = "v0.0.8",
                    commitShaShort = "abcdef0",
                    changelogSections = listOf(ChangelogSection("Fixes", listOf("Stable JSON"))),
                ),
            ),
            "lastSeenReleaseId",
            "pendingInstallReleaseId",
            "pendingInstallVersionCode",
            "ignoredReleaseId",
            "remindLaterReleaseId",
            "remindLaterUntilEpochMs",
            "pendingPostInstallChangelog",
            "lastInstalledChangelog",
        )
        assertJsonKeys(
            CodexShareFile(posts = listOf(CodexSharePost(source = "PIXIV", sourcePostId = "42"))),
            "version",
            "title",
            "posts",
        )
    }

    @Test
    fun tagCacheFixtureDecodesAndRewritesWithStableKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "release-json-test/tag_suggestions.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""",
        )
        val store = FileBackedTagSuggestionStore(storeFile = file, persistenceDebounceMs = 0L)
        try {
            store.awaitLoaded()
            assertEquals("legacy", store.get(SourceKey.GELBOORU, 1).single().text)
            store.put(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "seen", 10)))
            store.flush()
            val root = JSONObject(file.readText())
            val first = root.getJSONObject("sources").getJSONArray("GELBOORU").getJSONObject(0)
            assertEquals(
                setOf("text", "facet", "type", "count"),
                first.keySetCompat(),
            )
        } finally {
            store.close()
            file.parentFile?.deleteRecursively()
        }
    }

    private fun assertJsonKeys(value: Any, vararg keys: String) {
        assertEquals(keys.toSet(), JSONObject(gson.toJson(value)).keySetCompat())
    }
}

private fun JSONObject.keySetCompat(): Set<String> = buildSet {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}
