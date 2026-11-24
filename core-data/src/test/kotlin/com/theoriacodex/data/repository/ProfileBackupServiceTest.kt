package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.series.MAX_MEMO_LENGTH
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    @Test fun `backup with retired expansion metadata still decodes and restores`() = runTest {
        val expected = backup()
        val root = encodedTree(expected)
        val retired = "settingsSectionExpansion" to JsonObject(mapOf("UNIFIED_MODE" to JsonPrimitive(false)))
        val settings = JsonObject(root.getValue("settings").jsonObject + retired)
        val bytes = JsonObject(root + retired + ("settings" to settings)).toString().toByteArray(Charsets.UTF_8)
        assertEquals(expected, codec.decode(bytes))
        val harness = harness()
        val result = harness.service.restore(bytes)
        assertEquals(1, result.collectionsAdded)
        assertEquals(1, harness.pins.observeSavedSearches().first().size)
    }

    @Test fun `developer copy selects the imported active profile and repeats without duplicate identities`() = runTest {
        val harness = harness()
        val bytes = codec.encode(backup())
        harness.service.restore(bytes, applyPreferences = true, selectImportedProfile = true)
        val first = harness.settings.observeSettings().first()
        assertEquals("profile-main", first.activeProfileId)
        harness.service.restore(bytes, applyPreferences = true, selectImportedProfile = true)
        assertEquals(first, harness.settings.observeSettings().first())
        assertEquals(1, harness.pins.observeSavedSearches().first().size)
    }

    @Test fun `restoring the same backup twice adds nothing the second time`() = runTest {
        val directory = temporary.newFolder()
        val settings = InMemorySettingsRepository()
        val pins = InMemorySavedSearchRepository()
        val service = ProfileBackupService(settings, FakeBackupStore(), pins,
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"))
        val bytes = codec.encode(backup())

        service.restore(bytes)
        val afterFirst = settings.observeSettings().first()
        service.restore(bytes)

        assertEquals(afterFirst.recommendationProfiles, settings.observeSettings().first().recommendationProfiles)
        assertEquals(1, pins.observeSavedSearches().first().size)
    }

    @Test fun `a backup carries the extension repos, and one without them stays readable by the previous release`() {
        val repos = listOf(ExtensionRepo("https://a.example/repo", "A", "aa11"), ExtensionRepo("https://b.example/repo", "B", "bb22"))
        val written = backup().copy(extensionRepos = repos)
        assertEquals(repos, codec.decode(codec.encode(written)).extensionRepos)
        assertEquals(4, encodedTree(written)["version"]?.toString()?.toInt())

        val plain = encodedTree(backup())
        assertFalse("extensionRepos" in plain)
        assertEquals(2, plain["version"]?.toString()?.toInt())
        assertEquals(emptyList<ExtensionRepo>(), codec.decode(codec.encode(backup())).extensionRepos)
    }

    @Test fun `statistics with a watched-tag floor are written as version 5 and restore the floor`() {
        val statistics = LifetimeStatistics(
            watchedByTag = mapOf(StatisticsTagKey(SourceKey.GELBOORU, "fern") to 9L),
            watchedTagFloors = mapOf(SourceKey.GELBOORU to 4L),
        )
        val written = backup().copy(statistics = statistics)

        assertEquals(5, encodedTree(written)["version"]?.toString()?.toInt())
        assertEquals(statistics.watchedTagFloors, codec.decode(codec.encode(written)).statistics?.watchedTagFloors)
        assertEquals(2, encodedTree(backup().copy(statistics = LifetimeStatistics()))["version"]?.toString()?.toInt())
    }

    @Test fun `exporting writes the device's repos, previewing names the new ones, and restoring stores them`() = runTest {
        val held = listOf(ExtensionRepo("https://present.example/repo", "Present", "aa11"))
        val source = object : ExtensionRepos by RecordingRepos(refuse = "", added = "") {
            override suspend fun repos() = held
        }
        val directory = temporary.newFolder()
        val exporting = ProfileBackupService(InMemorySettingsRepository(), FakeBackupStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"), extensionRepos = source)
        val exported = codec.decode(exporting.export())
        assertEquals(held, exported.extensionRepos)
        val bytes = codec.encode(exported.copy(extensionRepos = held + listOf(
            ExtensionRepo("https://new.example/repo", "New", "bb22"),
            ExtensionRepo("https://unnamed.example/repo", "https://unnamed.example/repo", "dd44"),
            ExtensionRepo("https://bad.example/repo", "Bad", "cc33"),
        )))

        val repos = RecordingRepos(refuse = "https://bad.example/repo", added = "https://present.example/repo")
        val restoreDirectory = temporary.newFolder()
        val restoring = ProfileBackupService(InMemorySettingsRepository(), FakeBackupStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(restoreDirectory), restoreDirectory.resolve("restore.json"), extensionRepos = repos)
        val preview = (restoring.preview(bytes) as BackupPreview.Profiles).preview
        assertEquals(listOf("New", "https://unnamed.example/repo", "Bad"), preview.newRepos)
        val result = restoring.restore(bytes)

        assertEquals(listOf("https://new.example/repo", "https://unnamed.example/repo", "https://bad.example/repo"), repos.given.drop(1).map { it.url })
        assertEquals(2, result.reposAdded)
        assertEquals(mapOf("https://bad.example/repo" to "Names no signing key"), result.repoProblems)
    }

    @Test fun `startup recovery of an interrupted restore stores the backup's repos`() = runTest {
        var fail = true
        val base = harness()
        val store = object : ProfileBackupStore by base.store {
            override suspend fun merge(library: BackupLibrary): Int {
                if (fail) error("Simulated storage failure")
                return base.store.merge(library)
            }
        }
        val repos = RecordingRepos(refuse = "", added = "https://present.example/repo")
        val service = ProfileBackupService(base.settings, store, base.pins, base.positions, base.journal, extensionRepos = repos)
        val bytes = codec.encode(backup().copy(extensionRepos = listOf(ExtensionRepo("https://new.example/repo", "New", "bb22"))))

        assertTrue(runCatching { service.restore(bytes) }.exceptionOrNull() is ProfileRestorePendingException)
        assertEquals(listOf("https://present.example/repo"), repos.repos().map { it.url })
        fail = false
        assertTrue(service.recoverPendingRestore())

        assertEquals(listOf("https://present.example/repo", "https://new.example/repo"), repos.repos().map { it.url })
        assertFalse(base.journal.exists())
    }

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

    @Test fun `export strips device paths signed URLs and transient settings`() {
        val backup = backup()
        val localPost = backup.library.posts.single().copy(
            preview = ImageRef("https://example.test/p?token=secret", "/private/photo", "image/jpeg"),
            full = ImageRef("https://user:secret@example.test/full", "/private/full", "image/jpeg"),
            media = listOf(ImageRef("file:///private/photo", "/private/photo", "image/jpeg")),
            pageUrl = "https://example.test/page?auth=secret",
        )
        val source = backup.copy(library = backup.library.copy(posts = listOf(localPost)))
        val bytes = codec.encode(source)
        val json = bytes.toString(Charsets.UTF_8)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("/private/"))
        val restored = codec.decode(bytes)
        assertNull(restored.library.posts.single().preview.url)
        assertNull(restored.library.posts.single().full)
        assertNull(restored.library.posts.single().media.single().localPath)
    }

    @Test fun `restore merges into matching profiles and preserves current preferences and content`() = runTest {
        val harness = harness()
        val existing = harness.settings.observeSettings().first()
        harness.settings.updateSettings {
            it.copy(favoriteTagsByProfile = mapOf("profile-main" to listOf(FavoriteTagEntry(SourceKey.PIXIV, "mine"))))
        }
        harness.pins.save("Existing", search())
        val result = harness.service.restore(codec.encode(backup()))
        val settings = harness.settings.observeSettings().first()
        assertEquals(0, result.profilesAdded)
        assertEquals(1, result.collectionsAdded)
        assertEquals(existing.activeProfileId, settings.activeProfileId)
        assertEquals(existing.cache, settings.cache)
        assertEquals(existing.codexLibrarySort, settings.codexLibrarySort)
        assertEquals(existing.recommendationProfiles, settings.recommendationProfiles)
        val imported = harness.store.library
        assertEquals("legacy-codex", imported.codices.single().codexId)
        assertEquals("profile-main", imported.likes.single().profileId)
        assertEquals(listOf("mine", "cat"), settings.favoriteTagsByProfile.getValue("profile-main").map { it.tags.single() })
        assertEquals(2, harness.pins.observeSavedSearches().first().size)
        assertEquals(4, harness.positions.snapshot().single().mediaNumber)
        assertFalse(harness.journal.exists())
    }

    @Test fun `a profile this device lacks comes back under its own name`() = runTest {
        val harness = harness()
        val extra = RecommendationProfile("profile-extra", "Extra")
        val withExtra = backup().let { it.copy(settings = it.settings.copy(
            recommendationProfiles = it.settings.recommendationProfiles + extra,
        )) }
        val result = harness.service.restore(codec.encode(withExtra))
        assertEquals(1, result.profilesAdded)
        assertTrue(extra in harness.settings.observeSettings().first().recommendationProfiles)
    }

    @Test fun `a profile deleted this session is usable again once restored, and others stay retired`() = runTest {
        val directory = temporary.newFolder()
        val guard = ProfileLibraryWriteGuard().apply {
            retire("profile-alt")
            retire("profile-gone")
        }
        val service = ProfileBackupService(InMemorySettingsRepository(), FakeBackupStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"), writeGuard = guard)

        service.restore(codec.encode(backup()))

        assertFalse(guard.isRetired("profile-alt"))
        assertTrue(guard.isRetired("profile-gone"))
    }

    @Test fun `statistics travel with history and restoring them twice changes nothing`() = runTest {
        val directory = temporary.newFolder()
        val settings = InMemorySettingsRepository()
        val source = InMemoryStatisticsRepository()
        source.recordAppOpen()
        source.recordWatchedPost(SourceKey.PIXIV, setOf("cat"))
        val service = ProfileBackupService(settings, FakeBackupStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"), source)

        val exported = service.export(includeRecents = true)
        assertNull(codec.decode(service.export(includeRecents = false)).statistics)

        val target = InMemoryStatisticsRepository()
        target.recordAppOpen()
        target.recordAppOpen()
        val restoring = ProfileBackupService(settings, FakeBackupStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(temporary.newFolder()), directory.resolve("target.json"), target)
        restoring.restore(exported)
        val first = target.observeStatistics().first()
        assertEquals(2L, first.appOpenCount)
        assertEquals(1L, first.watchedPostCount)
        assertEquals(1L, first.watchedByTag[StatisticsTagKey(SourceKey.PIXIV, "cat")])
        restoring.restore(exported)
        assertEquals(first, target.observeStatistics().first())
    }

    @Test fun `global preferences are restored only when explicitly requested`() = runTest {
        val harness = harness()
        harness.service.restore(codec.encode(backup()), applyPreferences = true)
        val settings = harness.settings.observeSettings().first()
        assertTrue(settings.cache.cacheFullImageOnSave)
        assertTrue(settings.oldSearchBar)
        assertEquals(CodexLibrarySort.NEWEST, settings.codexLibrarySort)
        assertEquals("profile-main", settings.activeProfileId)
    }

    @Test fun `canonical booru post links survive while arbitrary credential queries do not`() {
        val original = backup().library.posts.single()
        listOf(SourceKey.GELBOORU to "gelbooru.com", SourceKey.RULE34XXX to "rule34.xxx").forEach { (source, host) ->
            val restored = portablePostSnapshot(original.copy(id = PostId(source, "42"),
                pageUrl = "https://$host/index.php?page=post&s=view&id=42&api_key=secret"))
            assertEquals("https://$host/index.php?page=post&s=view&id=42", restored.pageUrl)
        }
        assertNull(portablePostSnapshot(original.copy(pageUrl = "https://example.test/post?token=secret")).pageUrl)
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
        assertEquals(backup().settings.favoriteTagsByProfile, settings.observeSettings().first().favoriteTagsByProfile)
    }

    @Test fun `a journal from before identity merges finishes under the IDs it already committed`() = runTest {
        val harness = harness()
        val journal = JsonObject(
            mapOf(
                "operationId" to JsonPrimitive("legacy"),
                "applyPreferences" to JsonPrimitive(false),
                "backup" to encodedTree(backup()),
            ),
        )
        harness.journal.writeText(journal.toString())

        assertTrue(harness.service.recoverPendingRestore())

        assertEquals("restored-legacy-0", harness.store.library.likes.single().profileId)
        assertTrue(harness.settings.observeSettings().first().recommendationProfiles.any { it.profileId == "restored-legacy-0" })
        assertEquals(listOf("restored-legacy-0"), harness.pins.observeSavedSearches().first().map { it.id })
        assertFalse(harness.journal.exists())
    }

    @Test fun `a backup whose For You pins convert to fit the limit restores, and restores again`() = runTest {
        val harness = harness()
        val ordinary = List(MAX_SAVED_SEARCHES - 2) { SavedSearchEntry("pin-$it", "Pin $it", search().copy(queryHash = "hash-$it"), 5L) }
        val mix = forYouPin("mix", listOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.DANBOORU).associateWith { listOf("cat") })
        val tagless = forYouPin("tagless", mapOf(SourceKey.PIXIV to listOf(" ")))
        val bytes = codec.encode(backup().copy(savedSearches = ordinary + mix + tagless))

        harness.service.restore(bytes)
        val restored = harness.pins.observeSavedSearches().first()
        assertEquals(MAX_SAVED_SEARCHES, restored.size)
        assertFalse(restored.any { it.id == "tagless" })

        harness.service.restore(bytes)
        assertEquals(restored, harness.pins.observeSavedSearches().first())
    }

    @Test fun `invalid backup and capacity overflow fail before any durable restore mutation`() = runTest {
        val harness = harness()
        val tree = encodedTree(backup())
        val library = tree.getValue("library").jsonObject
        val posts = JsonArray(library.getValue("posts").jsonArray.drop(1))
        val corrupt = JsonObject(tree + ("library" to JsonObject(library + ("posts" to posts))))
        assertTrue(runCatching { harness.service.restore(corrupt.toString().toByteArray()) }.isFailure)
        assertFalse(harness.journal.exists())
        assertEquals(0, harness.store.merges)
        repeat(MAX_SAVED_SEARCHES) { harness.pins.save("Pin $it", search().copy(queryHash = "hash-$it")) }
        assertTrue(runCatching { harness.service.restore(codec.encode(backup())) }.isFailure)
        assertFalse(harness.journal.exists())
        assertEquals(0, harness.store.merges)
    }

    @Test fun `series round trip as version 3 and a backup without them is written and read as before`() {
        val plain = backup()
        val written = codec.encode(plain).toString(Charsets.UTF_8)
        assertTrue(written.contains("\"version\":2") && !written.contains("\"series\""))
        assertEquals(emptyList<BackupSeries>(), codec.decode(written.toByteArray()).library.series)

        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val series = BackupSeries(
            StoredSeries(
                Series(id, "Title", "https://example.test/cover.jpg", "Author", listOf("Drama"), "en", "Synopsis", SeriesStatus.ONGOING, """{"slug":"t"}"""),
                followed = true, readingMode = GalleryReadingMode.VERTICAL, lastOpenedEntry = "/chapter/2",
            ),
            listOf(
                StoredSeriesEntry(SeriesEntry("/chapter/2", "Ch.2", 2f, "Group", 5L, """{"id":2}"""), sourceOrder = 0, firstSeenAtEpochMs = 6L, position = 3, length = 20),
                StoredSeriesEntry(SeriesEntry("/chapter/1", "Ch.1"), sourceOrder = 1, firstSeenAtEpochMs = 4L, read = true, position = 18, length = 18),
                StoredSeriesEntry(SeriesEntry("/chapter/0", "Dropped"), sourceOrder = -1, firstSeenAtEpochMs = 4L),
            ),
        )
        val withSeries = plain.copy(library = plain.library.copy(series = listOf(series)))
        val encoded = codec.encode(withSeries)
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"version\":3"))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"coverUrl\":\"https://example.test/cover.jpg\""))
        assertEquals(listOf(series), codec.decode(encoded).library.series)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("lastCheckedAt"))
        val withCheckTime = encoded.toString(Charsets.UTF_8).replace(""""followed":true""", """"followed":true,"lastCheckedAt":9""")
        assertTrue(withCheckTime.contains("lastCheckedAt"))
        assertEquals(listOf(series), codec.decode(withCheckTime.toByteArray()).library.series)
        val withMemo = encoded.toString(Charsets.UTF_8)
        val emptyMemo = withMemo.replace(""""memo":"{\"slug\":\"t\"}"""", """"memo":"{}"""")
        assertTrue(emptyMemo != withMemo)
        assertEquals(null, codec.decode(emptyMemo.toByteArray()).library.series.single().series.series.memo)
        val longMemo = withMemo.replace(""""memo":"{\"slug\":\"t\"}"""", """"memo":"{\"slug\":\"${"t".repeat(MAX_MEMO_LENGTH)}\"}"""")
        assertTrue(longMemo != withMemo)
        assertEquals(null, codec.decode(longMemo.toByteArray()).library.series.single().series.series.memo)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("trustedExtensions"))
        val twice = withSeries.copy(library = withSeries.library.copy(series = listOf(series, series)))
        assertTrue(runCatching { codec.encode(twice) }.isFailure)
    }

    @Test fun `a series' choice to download new chapters round trips and older backups restore with it off`() {
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val series = BackupSeries(StoredSeries(Series(id, "Title"), followed = true, downloadNewChapters = true), emptyList())
        val encoded = codec.encode(backup().let { it.copy(library = it.library.copy(series = listOf(series))) }).toString(Charsets.UTF_8)

        assertEquals(listOf(series), codec.decode(encoded.toByteArray()).library.series)
        val older = encoded.replace("\"downloadNewChapters\":true,", "")
        assertTrue(older != encoded)
        assertFalse(codec.decode(older.toByteArray()).library.series.single().series.downloadNewChapters)
    }

    @Test fun `a backup the app writes is one it restores even for a series over the chapter limit`() {
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        fun entry(index: Int, order: Int, read: Boolean = false) =
            StoredSeriesEntry(SeriesEntry("/chapter/$index", "Ch.$index"), sourceOrder = order, firstSeenAtEpochMs = 1L, read = read)
        val entries = listOf(entry(-3, order = -1), entry(-2, order = -1), entry(-1, order = -1, read = true)) +
            List(MAX_BACKUP_SERIES_ENTRIES - 1) { entry(it, order = it) }
        val stored = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/chapter/-3")
        val huge = backup().let { it.copy(library = it.library.copy(series = listOf(BackupSeries(stored, entries)))) }
        val restored = codec.decode(codec.encode(huge)).library.series.single().entries.map { it.entry.address }
        assertEquals(MAX_BACKUP_SERIES_ENTRIES, restored.size)
        assertEquals(listOf("/chapter/-3", "/chapter/-1", "/chapter/0"), restored.take(3))
        assertTrue("/chapter/-2" !in restored && "/chapter/${MAX_BACKUP_SERIES_ENTRIES - 2}" !in restored)
        val shuffled = huge.copy(library = huge.library.copy(series = listOf(BackupSeries(stored, entries.reversed()))))
        assertEquals(restored, codec.decode(codec.encode(shuffled)).library.series.single().entries.map { it.entry.address })

        val odd = backup().let { plain ->
            plain.copy(library = plain.library.copy(series = listOf(
                BackupSeries(StoredSeries(Series(id, " ")), listOf(entry(1, 0), entry(2, 1).let { it.copy(entry = it.entry.copy(address = "")) })),
                BackupSeries(StoredSeries(Series(SeriesId(id.source, ""), "No address")), emptyList()),
            )))
        }
        val written = codec.decode(codec.encode(odd)).library.series.single()
        assertEquals("/title/1" to listOf("/chapter/1"), written.series.series.title to written.entries.map { it.entry.address })
        val blankTitle = codec.encode(odd).toString(Charsets.UTF_8).replace("\"title\":\"/title/1\"", "\"title\":\" \"")
        assertTrue(blankTitle.contains("\"title\":\" \"") && runCatching { codec.decode(blankTitle.toByteArray()) }.isFailure)
    }

    @Test fun `invalid versions malformed bytes unknown sources and deeply nested input are rejected`() {
        val invalid = listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            "{}".toByteArray(),
            "[".repeat(65).toByteArray(),
            codec.encode(backup()).toString(Charsets.UTF_8).replace("\"version\":2", "\"version\":6").toByteArray(),
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
        assertEquals(1, (harness.service.preview(codec.encode(exported)) as BackupPreview.Profiles).preview.savedPostCount)
    }

    private fun encodedTree(backup: ProfileBackup): JsonObject =
        Json.parseToJsonElement(codec.encode(backup).toString(Charsets.UTF_8)).jsonObject

    private fun harness(settings: SettingsRepository = InMemorySettingsRepository()): Harness {
        val directory = temporary.newFolder()
        val store = FakeBackupStore()
        val pins = InMemorySavedSearchRepository()
        val positions = FileBackedReadingPositionRepository(directory)
        val journal = directory.resolve("restore.json")
        return Harness(settings, store, pins, positions, journal,
            ProfileBackupService(settings, store, pins, positions, journal, clock = { 100L }))
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

internal class FakeBackupStore : ProfileBackupStore {
    var library = BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())
    var merges = 0
    override suspend fun snapshot(includeRecents: Boolean): BackupLibrary =
        if (includeRecents) library else library.copy(watched = emptyList(), searches = emptyList())
    override suspend fun merge(library: BackupLibrary): Int {
        merges += 1
        val added = library.codices.count { codex -> this.library.codices.none { it.codexId == codex.codexId } }
        this.library = library
        return added
    }
}

private fun backup(): ProfileBackup {
    val post = Post(PostId(SourceKey.PIXIV, "42"), ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
        null, pageUrl = "https://example.test/artworks/42", width = 100, height = 100, canonicalTags = listOf("cat"),
        rawTags = listOf("cat"), authorName = "Artist", createdAtEpochMs = 1L, mediaCount = 5)
    val codex = Codex("legacy-codex", "Favorites", 2L,
        listOf(CodexAutomaticTag(SourceKey.PIXIV, "cat"), CodexAutomaticTag(SourceKey.PIXIV, "dog")))
    val settings = AppSettings(
        oldSearchBar = true,
        codexLibrarySort = CodexLibrarySort.NEWEST,
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

private fun forYouPin(id: String, tags: Map<SourceKey, List<String>>) = SavedSearchEntry(
    id, id,
    RecentSearchEntry(
        Query(QueryMode.Unified, tags.values.flatten().filter(String::isNotBlank), emptyList(), SortMode.TOP, null, null),
        "for_you:$id", 5L, kind = RecentSearchKind.UNIFIED, sources = tags.keys.toList(), sourceTags = tags,
    ),
    5L,
)

private fun search(): RecentSearchEntry {
    val terms = listOf(SearchTerm("cat"), SearchTerm("dog"))
    val query = Query(QueryMode.Source(SourceKey.PIXIV), terms, emptyList(), SortMode.NEWEST, null, null,
        listOf(SearchTermGroup(terms)))
    return RecentSearchEntry(query, "search", 5L)
}
