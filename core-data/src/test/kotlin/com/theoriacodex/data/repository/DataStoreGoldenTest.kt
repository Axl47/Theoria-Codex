package com.theoriacodex.data.repository

import androidx.datastore.core.CorruptionException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.storage.GsonDataStoreSerializer
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
 * Pins how the Gson-backed DataStore files (golden/datastore/) load today: which odd inputs repair
 * into a loaded value, which reset the store to defaults (original bytes kept as `.corrupt`), and
 * which throw. Inputs are hand-built. A migration to kotlinx.serialization must keep every outcome
 * except those marked "latent bug": they pin a crash loop, and the migration may change them to a
 * repair or a reset, as long as the change is deliberate and the test is updated with it.
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

    // Gson writes null into non-null reference fields; validate() builds the domain and throws, so the store resets.
    @Test fun `settings null on a non-null list or string resets the whole store`() = runTest {
        listOf("settings-null-list", "settings-null-string").forEach { name ->
            val directory = directoryWith(DATASTORE_SETTINGS_FILE_NAME, name)
            val settings = withScope { DataStoreSettingsRepository(directory, it).observeSettings().first() }

            assertEquals(SettingsDataStoreFile().toDomain(), settings)
            assertFalse(name, settings.oldSearchBar)
            assertEquals(golden(name), directory.resolve("$DATASTORE_SETTINGS_FILE_NAME.corrupt").readText())
        }
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

    // Gson accepts "3" for Int, "true" for Boolean and "2.5" for Double.
    @Test fun `settings quoted numbers and booleans are read as their values`() = runTest {
        val settings = loadSettings("settings-quoted-numbers")

        assertTrue(settings.oldSearchBar)
        assertTrue(settings.runtime.sourceWeights.getValue(SourceKey.PIXIV) > SourceRuntimeSettings().sourceWeights.getValue(SourceKey.PIXIV))
    }

    // ---- UI restore ----

    // Same split as settings: null on a non-null map or string resets everything, including the valid lastTab.
    @Test fun `ui restore null on a non-null map or string resets the whole store`() = runTest {
        listOf("ui-restore-null-map", "ui-restore-null-string").forEach { name ->
            val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, name)
            withScope { scope ->
                val repository = DataStoreUiRestoreRepository(directory, scope)

                assertNull(name, repository.getLastTab())
                assertNull(name, repository.observeViewerLaunchContext().first())
            }
            assertEquals(golden(name), directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").readText())
        }
    }

    @Test fun `ui restore missing state loads empty without quarantine`() = runTest {
        val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, "ui-restore-missing-state")

        withScope { assertNull(DataStoreUiRestoreRepository(directory, it).getLastTab()) }

        assertFalse(directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").exists())
    }

    @Test fun `ui restore unknown and retired keys are ignored`() = runTest {
        val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, "ui-restore-retired-keys")

        withScope {
            val repository = DataStoreUiRestoreRepository(directory, it)
            assertEquals("codex", repository.getLastTab())
            assertEquals(SearchScrollState(7, 0), repository.getSearchScrollState("h"))
        }
    }

    @Test fun `ui restore quoted numbers are read as their values`() = runTest {
        val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, "ui-restore-quoted-numbers")

        withScope {
            val repository = DataStoreUiRestoreRepository(directory, it)
            assertEquals(SearchScrollState(7, 12), repository.getSearchScrollState("h"))
            assertEquals(3, repository.observeViewerLaunchContext().first()?.startIndex)
        }
    }

    // Latent bug: a null inside a nested record passes validate() and is never quarantined, so every
    // read throws NullPointerException (the list is non-null in Kotlin) and the file stays as written.
    @Test fun `ui restore null inside a nested record throws on read and is not quarantined`() = runTest {
        val directory = directoryWith(DATASTORE_UI_RESTORE_FILE_NAME, "ui-restore-null-nested-list")

        withScope { scope ->
            assertThrows(NullPointerException::class.java) {
                kotlinx.coroutines.runBlocking { DataStoreUiRestoreRepository(directory, scope).getFeedFabRestoreStates() }
            }
        }

        assertFalse(directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").exists())
        assertEquals(golden("ui-restore-null-nested-list"), directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME).readText())
    }

    // ---- Statistics ----

    @Test fun `statistics null on a defaulted counter repairs to zero`() = runTest {
        val statistics = loadStatistics("statistics-null-primitive")

        assertEquals(0L, statistics.appOpenCount)
        assertEquals(4L, statistics.watchedPostCount)
    }

    @Test fun `statistics null on a non-null map resets the whole store`() = runTest {
        val directory = directoryWith(DATASTORE_STATISTICS_FILE_NAME, "statistics-null-map")

        val statistics = withScope { DataStoreStatisticsRepository(directory, it).observeStatistics().first() }

        assertEquals(LifetimeStatistics(), statistics)
        assertEquals(golden("statistics-null-map"), directory.resolve("$DATASTORE_STATISTICS_FILE_NAME.corrupt").readText())
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

    // Latent bug: a null deep in a record passes validate() and is not quarantined; observing throws
    // NullPointerException and the store stays unreadable until the file is repaired by hand.
    @Test fun `statistics null inside a nested record throws on read and is not quarantined`() = runTest {
        val directory = directoryWith(DATASTORE_STATISTICS_FILE_NAME, "statistics-null-nested-map")

        withScope { scope ->
            assertThrows(NullPointerException::class.java) {
                kotlinx.coroutines.runBlocking { DataStoreStatisticsRepository(directory, scope).observeStatistics().first() }
            }
        }

        assertFalse(directory.resolve("$DATASTORE_STATISTICS_FILE_NAME.corrupt").exists())
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

    // Latent bug: "sites": null passes validate(); observing throws NullPointerException forever, with no quarantine.
    @Test fun `sites null list throws on read and is not quarantined`() = runTest {
        val directory = directoryWith(SITE_PROFILES_FILE_NAME, "sites-null-list")

        withScope { scope ->
            assertThrows(NullPointerException::class.java) {
                kotlinx.coroutines.runBlocking { DataStoreSiteProfileRepository(directory, scope).observeProfiles().first() }
            }
        }

        assertFalse(directory.resolve("$SITE_PROFILES_FILE_NAME.corrupt").exists())
    }

    // ---- Failure type ----

    // The serializer turns every reset case into CorruptionException; DataStore then hands it to the repository's reset handler.
    @Test fun `inputs that reset a store surface as CorruptionException`() = runTest {
        val gson = Gson()
        val settings = GsonDataStoreSerializer("settings", SettingsDataStoreFile(), object : TypeToken<SettingsDataStoreFile>() {}.type, gson) { it.validate() }
        val ui = GsonDataStoreSerializer("ui", UiRestoreDataStoreFile(), object : TypeToken<UiRestoreDataStoreFile>() {}.type, gson) { it.validate() }
        val statistics = GsonDataStoreSerializer("statistics", StatisticsDataStoreFile(), object : TypeToken<StatisticsDataStoreFile>() {}.type, gson) { it.validate() }

        listOf(
            settings to "settings-null-list", settings to "settings-null-string",
            ui to "ui-restore-null-map", ui to "ui-restore-null-string",
            statistics to "statistics-null-map",
        ).forEach { (serializer, name) ->
            assertThrows(name, CorruptionException::class.java) {
                kotlinx.coroutines.runBlocking { serializer.readFrom(golden(name).byteInputStream()) }
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
