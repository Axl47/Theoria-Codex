package com.theoriacodex.data.repository

import androidx.datastore.core.CorruptionException
import com.theoriacodex.data.storage.JsonDataStoreSerializer
import com.theoriacodex.data.storage.UnsupportedStoreSchemaException
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins how odd DataStore files (golden/datastore/, hand-built) load: which inputs repair into a
 * loaded value and which reset the store to defaults (original bytes kept as `.corrupt`). The
 * goldens were captured under Gson; never regenerate them to make a change pass.
 *
 * Under Gson a null on a defaulted primitive repaired, a null on a non-null list, map or string
 * reset the store, and a null nested inside a record passed validation and then threw
 * NullPointerException on every read without quarantine. kotlinx.serialization (DurableJson's
 * coerceInputValues) repairs every null on a defaulted field to its default, so all of those now
 * keep the rest of the file. Gson never wrote nulls, so only damaged or hand-edited files differ.
 */
class DataStoreGoldenTest {
    @get:Rule val temporary = TemporaryFolder()

    // ---- Settings ----

    // Gson skips an explicit null for a primitive field, so the field keeps its Kotlin default.
    @Test fun `settings null on a defaulted boolean repairs to the default`() = runTest {
        val settings = loadSettings("settings-null-primitive")

        assertFalse(settings.cache.cacheFullImageOnSave)
        assertTrue(settings.oldSearchBar)
    }

    // Gson reset the whole store here.
    @Test fun `settings null on a non-null list repairs to the default and keeps the rest`() = runTest {
        assertEquals(SettingsDataStoreFile().toDomain().copy(oldSearchBar = true), loadSettings("settings-null-list"))
    }

    // The null string is the retired lastSelectedTabRoute, so it is ignored like any retired key.
    @Test fun `settings null on a retired string loads`() = runTest {
        assertTrue(loadSettings("settings-null-string").oldSearchBar)
    }

    @Test fun `settings missing schema version and fields load with defaults`() = runTest {
        val settings = loadSettings("settings-missing-values")

        assertTrue(settings.oldSearchBar)
        assertEquals(SettingsDataStoreFile().toDomain().copy(oldSearchBar = true), settings)
    }

    @Test fun `settings unknown and retired keys are ignored`() = runTest {
        val settings = loadSettings("settings-retired-keys")

        assertTrue(settings.oldSearchBar)
        assertEquals(SettingsDataStoreFile().toDomain().copy(oldSearchBar = true), settings)
    }

    // The pre-profile-id key (`activeProfile`: USER_1 or USER_2) is retired, so such a file opens on the first profile.
    @Test fun `settings with only the retired profile key open on the first profile`() = runTest {
        assertEquals("profile-main", loadSettings("settings-retired-profile-key").activeProfileId)
    }

    // Gson accepts "3" for Int, "true" for Boolean and "2.5" for Double.
    @Test fun `settings quoted numbers and booleans are read as their values`() = runTest {
        val settings = loadSettings("settings-quoted-numbers")

        assertTrue(settings.oldSearchBar)
        assertTrue(settings.runtime.sourceWeights.getValue(SourceKey.PIXIV) > SourceRuntimeSettings().sourceWeights.getValue(SourceKey.PIXIV))
    }

    // ---- UI restore ----

    // Gson reset both stores, losing the valid lastTab.
    @Test fun `ui restore null on a non-null map or string repairs and keeps the rest`() = runTest {
        withUiRestore("ui-restore-null-map") { repository ->
            assertEquals("codex", repository.getLastTab())
            assertNull(repository.getSearchScrollState("h"))
        }
        withUiRestore("ui-restore-null-string") { repository ->
            assertEquals("codex", repository.getLastTab())
            assertEquals(ViewerLaunchContext("q", 4, ViewerStreamSource.SEARCH, 0), repository.observeViewerLaunchContext().first())
        }
    }

    @Test fun `ui restore missing state loads empty without quarantine`() = runTest {
        withUiRestore("ui-restore-missing-state") { assertNull(it.getLastTab()) }
    }

    @Test fun `ui restore unknown and retired keys are ignored`() = runTest {
        withUiRestore("ui-restore-retired-keys") { repository ->
            assertEquals("codex", repository.getLastTab())
            assertEquals(SearchScrollState(7, 0), repository.getSearchScrollState("h"))
        }
    }

    @Test fun `ui restore quoted numbers are read as their values`() = runTest {
        withUiRestore("ui-restore-quoted-numbers") { repository ->
            assertEquals(SearchScrollState(7, 12), repository.getSearchScrollState("h"))
            assertEquals(3, repository.observeViewerLaunchContext().first()?.startIndex)
        }
    }

    // Gson's latent bug: every read threw NullPointerException and the file was never quarantined.
    @Test fun `ui restore null inside a nested record repairs that field`() = runTest {
        withUiRestore("ui-restore-null-nested-list") { repository ->
            assertEquals(mapOf("ctx" to FeedFabRestoreState(animatedOnly = true)), repository.getFeedFabRestoreStates())
        }
    }

    // ---- Statistics ----

    @Test fun `statistics null on a defaulted counter repairs to zero`() = runTest {
        val statistics = loadStatistics("statistics-null-primitive")

        assertEquals(0L, statistics.appOpenCount)
        assertEquals(4L, statistics.watchedPostCount)
    }

    // Gson reset the whole store here.
    @Test fun `statistics null on a non-null map repairs and keeps the counters`() = runTest {
        assertEquals(LifetimeStatistics(watchedPostCount = 4L), loadStatistics("statistics-null-map"))
    }

    @Test fun `statistics missing statistics object loads zeroed`() = runTest {
        assertEquals(LifetimeStatistics(), loadStatistics("statistics-missing-statistics"))
    }

    @Test fun `statistics unknown and retired keys are ignored`() = runTest {
        assertEquals(4L, loadStatistics("statistics-retired-keys").watchedPostCount)
    }

    @Test fun `statistics quoted numbers are read as their values`() = runTest {
        val statistics = loadStatistics("statistics-quoted-numbers")

        assertEquals(5L, statistics.appOpenCount)
        assertEquals(mapOf(SourceKey.PIXIV to 3L), statistics.watchedBySource)
    }

    // Gson's latent bug: observing threw NullPointerException until the file was repaired by hand.
    @Test fun `statistics null inside a nested record repairs that field`() = runTest {
        val statistics = loadStatistics("statistics-null-nested-map")

        assertEquals(mapOf("c" to CodexStatistics(browsingMs = 5L)), statistics.codexStatistics)
        assertEquals(4L, statistics.watchedPostCount)
    }

    // ---- Custom site profiles ----

    private val safebooru = "custom_safebooru_org"

    @Test fun `site null on a defaulted boolean repairs to false`() = runTest {
        val site = loadSites("sites-null-primitive").single()

        assertEquals(safebooru, site.sourceId)
        assertFalse(site.credentialsRequired)
        assertNull(site.verifiedAtEpochMs)
    }

    // Records without an address or added time, and null entries, are skipped, not fatal, and kept in the file.
    @Test fun `sites missing required values are skipped and kept for a newer app`() = runTest {
        val directory = directoryWith(SITE_PROFILES_FILE_NAME, "sites-missing-required")

        withScope { scope ->
            val repository = DataStoreSiteProfileRepository(directory, scope)
            assertEquals(listOf(safebooru), repository.observeProfiles().first().map { it.sourceId })
            repository.update(safebooru) { it.copy(label = "Renamed") }
        }

        val written = directory.resolve(SITE_PROFILES_FILE_NAME).readText()
        assertTrue(written.contains("No added time"))
        assertTrue(written.contains("No address"))
        assertTrue(written.contains("Renamed"))
        assertFalse(directory.resolve("$SITE_PROFILES_FILE_NAME.corrupt").exists())
    }

    @Test fun `site unknown and retired keys are ignored`() = runTest {
        assertEquals(listOf(safebooru), loadSites("sites-retired-keys").map { it.sourceId })
    }

    @Test fun `site quoted numbers are read as their values`() = runTest {
        val site = loadSites("sites-quoted-numbers").single()

        assertEquals(5, site.searchTermLimit)
        assertEquals(1L, site.addedAtEpochMs)
    }

    // Gson's latent bug: observing threw NullPointerException forever. An empty list is the only meaningful default.
    @Test fun `sites null list repairs to no sites`() = runTest {
        assertEquals(emptyList<SiteProfile>(), loadSites("sites-null-list"))
    }

    // ---- Failure type ----

    // DataStore hands CorruptionException to the repository's reset handler; anything else propagates.
    @Test fun `unreadable or unsupported files surface as CorruptionException`() = runTest {
        val settings = JsonDataStoreSerializer(
            "settings", SETTINGS_DATASTORE_SCHEMA_VERSION, SettingsDataStoreFile(), SettingsDataStoreFile.serializer(),
            SettingsDataStoreFile::validate,
        )

        listOf(
            "", "{", "null", """{"schemaVersion":2}""",
            """{"schemaVersion":3,"settings":{"enabledSources":[null]}}""",
        ).forEach { json ->
            assertThrows(json, CorruptionException::class.java) {
                kotlinx.coroutines.runBlocking { settings.readFrom(json.byteInputStream()) }
            }
        }
        // A newer schema is refused before its payload is decoded, so a payload in a new shape is never reset.
        listOf("""{"schemaVersion":4}""", """{"schemaVersion":4,"settings":[]}""").forEach { json ->
            assertThrows(json, UnsupportedStoreSchemaException::class.java) {
                kotlinx.coroutines.runBlocking { settings.readFrom(json.byteInputStream()) }
            }
        }
    }

    private suspend fun loadSettings(name: String): AppSettings {
        val directory = directoryWith(DATASTORE_SETTINGS_FILE_NAME, name)
        return withScope { DataStoreSettingsRepository(directory, it).observeSettings().first() }
            .also { assertFalse(name, directory.resolve("$DATASTORE_SETTINGS_FILE_NAME.corrupt").exists()) }
    }

    private suspend fun loadStatistics(name: String): LifetimeStatistics {
        val directory = directoryWith(DATASTORE_STATISTICS_FILE_NAME, name)
        return withScope { DataStoreStatisticsRepository(directory, it).observeStatistics().first() }
            .also { assertFalse(name, directory.resolve("$DATASTORE_STATISTICS_FILE_NAME.corrupt").exists()) }
    }

    private suspend fun loadSites(name: String): List<SiteProfile> {
        val directory = directoryWith(SITE_PROFILES_FILE_NAME, name)
        return withScope { DataStoreSiteProfileRepository(directory, it).observeProfiles().first() }
            .also { assertFalse(name, directory.resolve("$SITE_PROFILES_FILE_NAME.corrupt").exists()) }
    }

    private suspend fun withUiRestore(name: String, block: suspend (DataStoreUiRestoreRepository) -> Unit) {
        val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, name)
        withScope { block(DataStoreUiRestoreRepository(directory, it)) }
        assertFalse(name, directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").exists())
    }

    private fun directoryWith(storeFileName: String, golden: String): File =
        temporary.newFolder().also { it.resolve(storeFileName).writeText(golden(golden)) }

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("golden/datastore/$name.json")) { "Missing golden $name" }.readText()

    private suspend fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return block(scope)
        } finally {
            scope.cancel()
        }
    }
}
