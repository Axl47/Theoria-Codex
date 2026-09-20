package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileBackupServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = ProfileBackupCodec()

    @Test fun `portable round trip preserves profile libraries rules settings and exact search replay`() {
        val backup = backup()
        val bytes = codec.encode(backup)
        val restored = codec.decode(bytes)
        assertEquals(backup, restored)
        assertEquals(listOf("Main", "Alt"), restored.preview().profileNames)
        assertEquals(1, restored.preview().collectionCount)
        assertEquals(1, restored.preview().savedPostCount)
        assertEquals(1, restored.preview().likeCount)
        assertEquals(1, restored.preview().followedCreatorCount)
        assertEquals(2, restored.preview().recentCount)
        assertEquals(1, restored.preview().savedSearchCount)
    }

    @Test fun `export strips device paths signed URLs and retired or transient settings`() {
        val backup = backup()
        val localPost = backup.library.posts.single().copy(
            preview = ImageRef("https://example.test/p?token=secret", "/private/photo", "image/jpeg"),
            full = ImageRef("https://user:secret@example.test/full", "/private/full", "image/jpeg"),
            media = listOf(ImageRef("file:///private/photo", "/private/photo", "image/jpeg")),
            pageUrl = "https://example.test/page?auth=secret",
        )
        val source = backup.copy(settings = backup.settings.copy(
            viewer = ViewerSettings(automaticTextTranslationEnabled = true), scenarioPreset = ScenarioPreset.SLOW_NETWORK,
        ), library = backup.library.copy(posts = listOf(localPost)))
        val bytes = codec.encode(source)
        val json = bytes.toString(Charsets.UTF_8)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("/private/"))
        val restored = codec.decode(bytes)
        assertNull(restored.library.posts.single().preview.url)
        assertNull(restored.library.posts.single().full)
        assertNull(restored.library.posts.single().media.single().localPath)
        assertFalse(restored.settings.viewer.automaticTextTranslationEnabled)
        assertEquals(ScenarioPreset.NORMAL, restored.settings.scenarioPreset)
    }

    @Test fun `restore adds fresh profile identities and preserves current preferences and content`() = runTest {
        val harness = harness()
        val existing = harness.settings.observeSettings().first()
        harness.pins.save("Existing", search())
        val result = harness.service.restore(codec.encode(backup()))
        val settings = harness.settings.observeSettings().first()
        assertEquals(2, result.profilesAdded)
        assertEquals(1, result.collectionsAdded)
        assertEquals(existing.activeProfileId, settings.activeProfileId)
        assertEquals(existing.cache, settings.cache)
        assertEquals(4, settings.recommendationProfiles.size)
        val imported = harness.store.library
        val profileId = "restored-operation-0"
        assertTrue(ProfileLibraryIds.belongsTo(imported.codices.single().codexId, profileId))
        assertEquals(profileId, imported.likes.single().profileId)
        assertEquals(imported.codices.single().codexId, imported.items.single().codexId)
        assertEquals(backup().settings.favoriteTagsByProfile["profile-main"], settings.favoriteTagsByProfile[profileId])
        assertEquals(2, harness.pins.observeSavedSearches().first().size)
        assertEquals(4, harness.positions.snapshot().single().mediaNumber)
        assertFalse(harness.journal.exists())
    }

    @Test fun `global preferences are restored only when explicitly requested`() = runTest {
        val harness = harness()
        harness.service.restore(codec.encode(backup()), applyPreferences = true)
        assertTrue(harness.settings.observeSettings().first().cache.cacheFullImageOnSave)
        assertEquals("profile-main", harness.settings.observeSettings().first().activeProfileId)
    }

    @Test fun `canonical booru post links survive while arbitrary credential queries do not`() {
        val original = backup().library.posts.single()
        listOf(SourceKey.GELBOORU to "gelbooru.com", SourceKey.RULE34XXX to "rule34.xxx").forEach { (source, host) ->
            val restored = portableBackupPost(original.copy(id = PostId(source, "42"),
                pageUrl = "https://$host/index.php?page=post&s=view&id=42&api_key=secret"))
            assertEquals("https://$host/index.php?page=post&s=view&id=42", restored.pageUrl)
        }
        assertNull(portableBackupPost(original.copy(pageUrl = "https://example.test/post?token=secret")).pageUrl)
        assertEquals("https://gelbooru.com/index.php?page=account&s=profile&id=3", portableBackupCreator(
            CreatorProfile(SourceKey.GELBOORU, "Artist", "3", profileUrl = "https://gelbooru.com/?token=secret"),
        ).profileUrl)
    }

    @Test fun `failure after database commit resumes idempotently from durable journal`() = runTest {
        val realSettings = InMemorySettingsRepository()
        var fail = true
        val settings = object : SettingsRepository by realSettings {
            override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
                if (fail) error("Simulated storage failure")
                realSettings.updateSettings(transform)
            }
        }
        val harness = harness(settings)
        val bytes = codec.encode(backup())
        assertTrue(runCatching { harness.service.restore(bytes) }.exceptionOrNull() is ProfileRestorePendingException)
        assertTrue(harness.journal.isFile)
        assertEquals(2, settings.observeSettings().first().recommendationProfiles.size)
        assertEquals(1, harness.store.library.codices.size)
        fail = false
        val restarted = ProfileBackupService(settings, harness.store, harness.pins, harness.positions, harness.journal)
        assertTrue(restarted.recoverPendingRestore())
        assertFalse(restarted.recoverPendingRestore())
        assertEquals(1, harness.store.library.codices.size)
        assertEquals(1, harness.pins.observeSavedSearches().first().size)
        assertEquals(4, settings.observeSettings().first().recommendationProfiles.size)
    }

    @Test fun `invalid backup and capacity overflow fail before any durable restore mutation`() = runTest {
        val harness = harness()
        val corrupt = JsonParser.parseString(codec.encode(backup()).toString(Charsets.UTF_8)).asJsonObject
        corrupt.getAsJsonObject("library").getAsJsonArray("posts").remove(0)
        assertTrue(runCatching { harness.service.restore(corrupt.toString().toByteArray()) }.isFailure)
        assertFalse(harness.journal.exists())
        assertEquals(0, harness.store.merges)
        repeat(MAX_SAVED_SEARCHES) { harness.pins.save("Pin $it", search().copy(queryHash = "hash-$it")) }
        assertTrue(runCatching { harness.service.restore(codec.encode(backup())) }.isFailure)
        assertFalse(harness.journal.exists())
        assertEquals(0, harness.store.merges)
    }

    @Test fun `invalid versions malformed bytes unknown sources and deeply nested input are rejected`() {
        val invalid = listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            "{}".toByteArray(),
            "[".repeat(65).toByteArray(),
            codec.encode(backup()).toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2").toByteArray(),
            codec.encode(backup()).toString(Charsets.UTF_8).replace("\"PIXIV\"", "\"UNKNOWN\"").toByteArray(),
        )
        invalid.forEach { assertTrue(runCatching { codec.decode(it) }.isFailure) }
    }

    @Test fun `export can exclude history while retaining saved searches and checks stable settings`() = runTest {
        val harness = harness()
        harness.settings.updateSettings { backup().settings }
        harness.store.library = backup().library
        harness.pins.merge(backup().savedSearches)
        harness.positions.merge(backup().readingPositions)
        val exported = codec.decode(harness.service.export(includeRecents = false))
        assertTrue(exported.library.watched.isEmpty())
        assertTrue(exported.library.searches.isEmpty())
        assertTrue(exported.readingPositions.isEmpty())
        assertEquals(1, exported.savedSearches.size)
        assertEquals(1, harness.service.preview(codec.encode(exported)).savedPostCount)
    }

    private fun harness(settings: SettingsRepository = InMemorySettingsRepository()): Harness {
        val directory = temporary.newFolder()
        val store = FakeBackupStore()
        val pins = InMemorySavedSearchRepository()
        val positions = FileBackedReadingPositionRepository(directory)
        val journal = directory.resolve("restore.json")
        return Harness(settings, store, pins, positions, journal,
            ProfileBackupService(settings, store, pins, positions, journal, clock = { 100L }, newOperationId = { "operation" }))
    }
}

private data class Harness(
    val settings: SettingsRepository,
    val store: FakeBackupStore,
    val pins: SavedSearchRepository,
    val positions: ReadingPositionRepository,
    val journal: java.io.File,
    val service: ProfileBackupService,
)

private class FakeBackupStore : ProfileBackupStore {
    var library = BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())
    var merges = 0
    override suspend fun snapshot(includeRecents: Boolean): BackupLibrary =
        if (includeRecents) library else library.copy(watched = emptyList(), searches = emptyList())
    override suspend fun merge(library: BackupLibrary) {
        merges += 1
        this.library = library
    }
}

private fun backup(): ProfileBackup {
    val post = Post(PostId(SourceKey.PIXIV, "42"), ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
        null, pageUrl = "https://example.test/artworks/42", width = 100, height = 100, canonicalTags = listOf("cat"),
        rawTags = listOf("cat"), authorName = "Artist", createdAtEpochMs = 1L, mediaCount = 5)
    val codex = Codex("legacy-codex", "Favorites", 2L,
        listOf(CodexAutomaticTag(SourceKey.PIXIV, "cat"), CodexAutomaticTag(SourceKey.PIXIV, "dog")))
    val settings = AppSettings(
        cache = CacheSettings(cacheFullImageOnSave = true),
        favoriteTagsByProfile = mapOf("profile-main" to listOf(FavoriteTagEntry(SourceKey.PIXIV, "cat"))),
        forYouBlacklistByProfile = mapOf("profile-main" to listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("hidden")))),
        followedCreators = listOf(FollowedCreator(CreatorProfile(SourceKey.PIXIV, "Artist", "3", uploadsQuery = "user:3"), "follow")),
    )
    return ProfileBackup(100L, RepositoryPolicies.normalizeSettings(settings),
        BackupLibrary(listOf(codex), listOf(CodexItem(codex.codexId, post.id, 3L)), listOf(post),
            listOf(LikedPost("profile-main", post.id, 3L, listOf("cat"))),
            listOf(RecentPostEntry(post, 4L, ViewerStreamSource.SEARCH, "search", maxViewedMediaNumber = 5)),
            listOf(search())),
        listOf(SavedSearchEntry("pin", "Cats or dogs", search(), 5L)),
        listOf(ReadingPosition(post.id, RecentPostSection.WATCHED, 4, 6L)),
    )
}

private fun search(): RecentSearchEntry {
    val terms = listOf(SearchTerm("cat"), SearchTerm("dog"))
    val query = Query(QueryMode.Source(SourceKey.PIXIV), terms, emptyList(), SortMode.NEWEST, null, null,
        listOf(SearchTermGroup(terms)))
    return RecentSearchEntry(query, "search", 5L)
}
