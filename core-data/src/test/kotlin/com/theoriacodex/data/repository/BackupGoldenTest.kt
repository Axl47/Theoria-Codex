package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupGoldenTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = ProfileBackupCodec()

    @Test fun `version 2 backup decodes to its source values`() {
        assertEquals(goldenBackup(), codec.decode(golden("backup-v2.json")))
    }

    @Test fun `version 1 backup decodes to the same values minus custom sources`() {
        assertEquals(goldenBackup().copy(customSources = emptyList()), codec.decode(golden("backup-v1.json")))
    }

    @Test fun `backups holding retired settings and statistics keys still import (ADR 0024)`() {
        listOf("backup-v1.json", "backup-v2.json").forEach { name ->
            val raw = golden(name).decodeToString()
            (RETIRED_SETTINGS + RETIRED_STATISTICS).forEach { key -> assertTrue("$name lacks $key", raw.contains("\"$key\"")) }
            val rewritten = codec.encode(codec.decode(golden(name))).decodeToString()
            (RETIRED_SETTINGS + RETIRED_STATISTICS).forEach { key -> assertFalse("$name kept $key", rewritten.contains("\"$key\"")) }
        }
    }

    @Test fun `a backup written now is the document the Gson codec wrote (ADR 0024, 0033)`() {
        val golden = golden("backup-v2.json")
        val written = DurableJson.parseToJsonElement(golden.decodeToString()).jsonObject
        val settings = written.getValue("settings").jsonObject - (RETIRED_SETTINGS + "lastSelectedTabRoute")
        val statistics = written.getValue("statistics").jsonObject - RETIRED_STATISTICS +
            listOf("readingMs", "animeMs").associateWith { JsonPrimitive(0L) }
        assertEquals(JsonObject(written + ("settings" to JsonObject(settings)) + ("statistics" to JsonObject(statistics))),
            DurableJson.parseToJsonElement(codec.encode(codec.decode(golden)).decodeToString()))
    }

    @Test fun `malformed JSON fails as before while invalid fields keep their message (ADR 0024)`() {
        val v2 = golden("backup-v2.json").decodeToString()
        val wrongTypes = listOf("\"appOpenCount\":0" to "\"appOpenCount\":{}", "\"settings\":{" to "\"settings\":[],\"x\":{",
            "\"favoriteTagsByProfile\":{" to "\"favoriteTagsByProfile\":[],\"x\":{")
        (listOf("{broken", "[]") + wrongTypes.map { (from, to) -> v2.replace(from, to) }).forEach { raw ->
            val failure = runCatching { codec.decode(raw.toByteArray()) }.exceptionOrNull()
            assertTrue(raw, failure != null && failure !is IllegalArgumentException)
        }
        val other = v2.replace("theoria-profile-backup", "other-format").toByteArray()
        assertEquals("This is not a Theoria profile backup", runCatching { codec.decode(other) }.exceptionOrNull()?.message)
        val noFavorites = v2.replace("\"favoriteTagsByProfile\":{", "\"retired\":{").toByteArray()
        assertEquals("Backup is missing favoriteTagsByProfile", runCatching { codec.decode(noFavorites) }.exceptionOrNull()?.message)
    }

    @Test fun `a backup's subtitle size must be one the Viewer offers (ADR 0224)`() {
        val v2 = golden("backup-v2.json").decodeToString()
        fun withSize(size: Int) = v2.replace("\"settings\":{", "\"settings\":{\"viewerSubtitleSizePercent\":$size,").toByteArray()
        assertEquals("Invalid backup preference: viewerSubtitleSizePercent", runCatching { codec.decode(withSize(500)) }.exceptionOrNull()?.message)
        assertTrue(runCatching { codec.decode(withSize(130)) }.isSuccess)
    }

    @Test fun `a backup with only the retired profile key is refused`() {
        val v2 = golden("backup-v2.json").decodeToString()
        val retired = v2.replace("\"activeProfileId\":\"profile-main\"", "\"activeProfile\":\"USER_2\"")
        assertTrue(retired != v2)
        assertEquals("Invalid backup field: activeProfileId", runCatching { codec.decode(retired.toByteArray()) }.exceptionOrNull()?.message)
    }

    @Test fun `journal written mid-restore still matches its backup and finishes the restore`() = runTest {
        val harness = harness()
        harness.journal.writeBytes(golden("pending_profile_restore.json"))

        val result = harness.service.restore(golden("backup-v2.json"), applyPreferences = true, selectImportedProfile = true)

        assertEquals(goldenBackup().library, harness.store.merged.single())
        assertEquals(listOf(goldenBackup().customSources.single()), harness.sites.observeProfiles().first())
        assertEquals(1, result.collectionsAdded)
        assertFalse(harness.journal.exists())
    }

    @Test fun `journal refuses a different backup or different flags`() = runTest {
        val harness = harness()
        harness.journal.writeBytes(golden("pending_profile_restore.json"))

        assertTrue(runCatching { harness.service.restore(golden("backup-v1.json"), true, true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { harness.service.restore(golden("backup-v2.json")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(harness.journal.exists())
        assertTrue(harness.store.merged.isEmpty())
    }

    private fun harness(): RestoreHarness {
        val journal = temporary.newFolder().resolve("pending_profile_restore.json")
        val store = RecordingBackupStore()
        val sites = InMemorySiteProfileRepository()
        val service = ProfileBackupService(
            InMemorySettingsRepository(), store, InMemorySavedSearchRepository(), InMemoryReadingPositionRepository(),
            journal, clock = { 100L }, siteProfiles = sites,
        )
        return RestoreHarness(service, store, sites, journal)
    }

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResource("golden/backup/$name")) { "Missing golden $name" }.readBytes()
}

private val RETIRED_SETTINGS = setOf("viewerAutomaticTextTranslationEnabled", "viewerEnabledOcrLanguages", "scenarioPreset", "viewerAutoRotateVideos")
private val RETIRED_STATISTICS = setOf("translatedPhraseCount", "translatedSourceCharacterCount")

private class RecordingBackupStore : ProfileBackupStore {
    val merged = mutableListOf<BackupLibrary>()
    override suspend fun snapshot(includeRecents: Boolean) = BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())
    override suspend fun merge(library: BackupLibrary): Int = merged.add(library).let { library.codices.size }
}

private class RestoreHarness(
    val service: ProfileBackupService,
    val store: RecordingBackupStore,
    val sites: InMemorySiteProfileRepository,
    val journal: java.io.File,
)

internal fun goldenBackup(): ProfileBackup {
    val tricky = "a&b=c<d>'e"
    val post = Post(
        PostId(SourceKey.PIXIV, "42"), ImageRef("https://example.test/preview.jpg", null, "image/jpeg"), null,
        pageUrl = "https://example.test/artworks/42", width = 100, height = 100, canonicalTags = listOf("cat", tricky),
        rawTags = listOf("cat", tricky), authorName = null, createdAtEpochMs = 1L, mediaCount = 5,
    )
    val codex = Codex("legacy-codex", "Cats & Dogs <fav> = 'best'", 2L, listOf(CodexAutomaticTag(SourceKey.PIXIV, tricky)))
    val terms = listOf(SearchTerm("cat"), SearchTerm(tricky))
    val search = RecentSearchEntry(
        Query(QueryMode.Source(SourceKey.PIXIV), terms, emptyList(), SortMode.NEWEST, null, null, listOf(SearchTermGroup(terms))),
        "search-hash", 5L,
    )
    val address = (SiteAddress.parse("safebooru.org") as SiteAddressParse.Valid).address
    return ProfileBackup(
        createdAtEpochMs = 100L,
        settings = RepositoryPolicies.normalizeSettings(
            AppSettings(
                oldSearchBar = true,
                cache = CacheSettings(cacheFullImageOnSave = true),
                favoriteTagsByProfile = mapOf("profile-main" to listOf(FavoriteTagEntry(SourceKey.PIXIV, tricky))),
                forYouBlacklistByProfile = mapOf("profile-main" to listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf(tricky)))),
            ),
        ),
        library = BackupLibrary(
            codices = listOf(codex),
            items = listOf(CodexItem(codex.codexId, post.id, 3L)),
            posts = listOf(post),
            likes = listOf(LikedPost("profile-main", post.id, 3L, listOf("cat", tricky))),
            watched = listOf(RecentPostEntry(post, 4L, ViewerStreamSource.SEARCH, null, maxViewedMediaNumber = 5)),
            searches = listOf(search),
        ),
        savedSearches = listOf(SavedSearchEntry("pin", "Cats & dogs <or> 'both'", search, 5L)),
        readingPositions = listOf(ReadingPosition(post.id, RecentPostSection.WATCHED, 4, 6L)),
        statistics = LifetimeStatistics(
            watchedPostCount = 2L,
            watchedBySource = mapOf(SourceKey.PIXIV to 2L),
            watchedByTag = mapOf(StatisticsTagKey(SourceKey.PIXIV, tricky) to 2L),
            searchCount = 1L,
        ),
        customSources = listOf(
            SiteProfile(address.derivedSourceId, address, "Safe & 'sound' <site>", "GELBOORU_02", addedAtEpochMs = 1L),
        ),
    )
}
