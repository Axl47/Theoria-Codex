package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileBackupSiteProfilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = ProfileBackupCodec()

    @Test
    fun `stored sites travel in v2 backups without credentials and restore onto another device`() = runTest {
        val safebooru = site("safebooru.org").copy(credentialsRequired = true)
        val source = service(InMemorySiteProfileRepository(listOf(safebooru)))

        val bytes = source.service.export()
        val json = bytes.toString(Charsets.UTF_8)
        val target = service(InMemorySiteProfileRepository())
        target.service.restore(bytes)

        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(2, root.get("version").asInt)
        assertEquals("custom_safebooru_org", root.getAsJsonArray("customSources").single().asJsonObject.get("sourceId").asString)
        assertFalse(json.contains("apiKey", ignoreCase = true))
        assertEquals(listOf(safebooru), target.sites.observeProfiles().first())
    }

    @Test
    fun `version 1 backups still decode and versions past 2 are refused`() {
        val v2 = codec.encode(emptyBackup()).toString(Charsets.UTF_8)

        assertEquals(emptyList<SiteProfile>(), codec.decode(v2.replace("\"version\":2", "\"version\":1").toByteArray()).customSources)
        assertTrue(runCatching { codec.decode(v2.replace("\"version\":2", "\"version\":3").toByteArray()) }.isFailure)
    }

    @Test
    fun `an invalid or duplicated site rejects the whole backup`() {
        val json = codec.encode(emptyBackup().copy(customSources = listOf(site("safebooru.org")))).toString(Charsets.UTF_8)

        val insecure = json.replace("\"baseUrl\":\"https://safebooru.org\"", "\"baseUrl\":\"http://safebooru.org\"")
        val record = JsonParser.parseString(json).asJsonObject.getAsJsonArray("customSources").single().toString()
        val duplicated = json.replace("\"customSources\":[$record]", "\"customSources\":[$record,$record]")

        assertTrue(runCatching { codec.decode(insecure.toByteArray()) }.isFailure)
        assertTrue(runCatching { codec.decode(duplicated.toByteArray()) }.isFailure)
        assertTrue(runCatching { codec.encode(emptyBackup().copy(customSources = listOf(site("a.example"), site("a.example")))) }.isFailure)
    }

    @Test
    fun `a restore that would pass the site limit fails before any journal is written`() = runTest {
        val stored = (1 until SiteProfile.MAX_CUSTOM_SITES).map { site("site$it.example") }
        val harness = service(InMemorySiteProfileRepository(stored))
        val bytes = codec.encode(emptyBackup().copy(customSources = listOf(site("new-a.example"), site("new-b.example"))))

        val failure = runCatching { harness.service.restore(bytes) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(harness.journal.exists())
        assertEquals(stored, harness.sites.observeProfiles().first())
        // Sites already on the device add nothing, so they never count against the limit.
        harness.service.restore(codec.encode(emptyBackup().copy(customSources = stored.take(2))))
    }

    @Test
    fun `journal recovery finishes even if sites filled the device after preflight`() = runTest {
        val sites = InMemorySiteProfileRepository()
        val failingOnce = object : ProfileBackupStore {
            var fail = true
            override suspend fun snapshot(includeRecents: Boolean) = emptyLibrary()
            override suspend fun merge(library: BackupLibrary): Int {
                if (fail) error("Simulated storage failure")
                return 0
            }
        }
        val harness = service(sites, failingOnce)
        val restored = site("restored.example")

        val pending = runCatching { harness.service.restore(codec.encode(emptyBackup().copy(customSources = listOf(restored)))) }
        assertTrue(pending.exceptionOrNull() is ProfileRestorePendingException)
        assertTrue(harness.journal.isFile)
        (1..SiteProfile.MAX_CUSTOM_SITES).forEach { sites.add(site("added$it.example")) }
        failingOnce.fail = false

        assertTrue(harness.service.recoverPendingRestore())

        assertFalse(harness.journal.exists())
        assertEquals(SiteProfile.MAX_CUSTOM_SITES + 1, sites.observeProfiles().first().size)
        assertEquals(SiteProfileAddResult.LimitReached, sites.add(site("one-more.example")))
    }

    @Test
    fun `a conflicting site keeps the device profile and is reported`() = runTest {
        val local = site("safebooru.org", family = "GELBOORU_02")
        val harness = service(InMemorySiteProfileRepository(listOf(local)))

        val result = harness.service.restore(
            codec.encode(emptyBackup().copy(customSources = listOf(local.copy(familyId = "DANBOORU", label = "Imported")))),
        )

        assertEquals(listOf(local.sourceId), result.keptExistingSites)
        assertEquals(listOf(local), harness.sites.observeProfiles().first())
    }

    private fun service(
        sites: InMemorySiteProfileRepository,
        store: ProfileBackupStore = object : ProfileBackupStore {
            override suspend fun snapshot(includeRecents: Boolean) = emptyLibrary()
            override suspend fun merge(library: BackupLibrary) = 0
        },
    ): SiteHarness {
        val directory = temporary.newFolder()
        val journal = directory.resolve("restore.json")
        val service = ProfileBackupService(
            InMemorySettingsRepository(), store, InMemorySavedSearchRepository(), InMemoryReadingPositionRepository(),
            journal, clock = { 100L }, siteProfiles = sites,
        )
        return SiteHarness(service, sites, journal)
    }

    private fun emptyBackup() = ProfileBackup(100L, RepositoryPolicies.normalizeSettings(AppSettings()), emptyLibrary())

    private fun emptyLibrary() = BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())

    private fun site(host: String, family: String = "GELBOORU_02"): SiteProfile {
        val address = (SiteAddress.parse(host) as SiteAddressParse.Valid).address
        return SiteProfile(address.derivedSourceId, address, "Site", family, addedAtEpochMs = 1L)
    }
}

private data class SiteHarness(
    val service: ProfileBackupService,
    val sites: InMemorySiteProfileRepository,
    val journal: File,
)
