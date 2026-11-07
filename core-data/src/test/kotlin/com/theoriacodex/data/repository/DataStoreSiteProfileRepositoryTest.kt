package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSiteProfileRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `profiles persist in the documented file shape and reload in added order`() = runBlocking {
        val directory = temporary.newFolder()
        val safebooru = profile("safebooru.org", family = "GELBOORU_02", addedAt = 1_762_500_000_000)
            .copy(verifiedAtEpochMs = 1_762_500_000_000)
        val booru = profile("booru.example", headers = SiteHeaders(userAgent = "Custom/1"))

        withRepository(directory) { repository ->
            assertEquals(SiteProfileAddResult.Added, repository.add(safebooru))
            assertEquals(SiteProfileAddResult.Added, repository.add(booru))
        }

        val json = JsonParser.parseString(directory.resolve(SITE_PROFILES_FILE_NAME).readText()).asJsonObject
        assertEquals(1, json.get("schemaVersion").asInt)
        val first = json.getAsJsonArray("sites")[0].asJsonObject
        assertEquals("custom_safebooru_org", first.get("sourceId").asString)
        assertEquals("https://safebooru.org", first.get("baseUrl").asString)
        assertEquals("GELBOORU_02", first.get("family").asString)
        assertEquals(1_762_500_000_000, first.get("addedAt").asLong)
        withRepository(directory) { repository -> assertEquals(listOf(safebooru, booru), repository.observeProfiles().first()) }
    }

    @Test
    fun `mutations follow the shared list rules`() = runBlocking {
        withRepository(temporary.newFolder()) { repository ->
            val site = profile("booru.example")
            repository.add(site)

            assertEquals(SiteProfileAddResult.Duplicate(site.sourceId), repository.add(site.copy(label = "Again")))
            assertTrue(repository.update(site.sourceId) { it.copy(label = "Renamed") })
            assertFalse(repository.update("custom_missing_example") { it })
            val restored = repository.mergeRestored(listOf(site.copy(familyId = "DANBOORU"), profile("other.example")))
            assertEquals(SiteProfileMergeResult(added = 1, keptExisting = listOf(site.sourceId)), restored)
            assertEquals(listOf("Renamed", "other.example"), repository.observeProfiles().first().map(SiteProfile::label))
            assertTrue(repository.remove(site.sourceId))
            assertFalse(repository.remove(site.sourceId))
        }
    }

    @Test
    fun `the site limit applies to adds but never to restores`() = runBlocking {
        withRepository(temporary.newFolder()) { repository ->
            val full = (1..SiteProfile.MAX_CUSTOM_SITES).map { profile("site$it.example") }
            full.forEach { assertEquals(SiteProfileAddResult.Added, repository.add(it)) }

            assertEquals(SiteProfileAddResult.LimitReached, repository.add(profile("extra.example")))
            assertEquals(1, repository.mergeRestored(listOf(profile("restored.example"))).added)
            assertEquals(SiteProfile.MAX_CUSTOM_SITES + 1, repository.observeProfiles().first().size)
        }
    }

    @Test
    fun `unreadable records are skipped but written back untouched`() = runBlocking {
        val directory = temporary.newFolder()
        val unreadable = """{"sourceId":"custom_future_example","baseUrl":"https://future.example","label":"","family":"X","addedAt":1}"""
        directory.resolve(SITE_PROFILES_FILE_NAME).writeText("""{"schemaVersion":1,"sites":[$unreadable,null]}""")

        withRepository(directory) { repository ->
            assertEquals(emptyList<SiteProfile>(), repository.observeProfiles().first())
            repository.add(profile("booru.example"))
        }

        val sites = JsonParser.parseString(directory.resolve(SITE_PROFILES_FILE_NAME).readText()).asJsonObject.getAsJsonArray("sites")
        assertEquals(listOf("custom_booru_example", "custom_future_example"), sites.map { it.asJsonObject.get("sourceId").asString })
    }

    @Test
    fun `a newer schema is refused rather than overwritten`() = runBlocking {
        val directory = temporary.newFolder()
        val newer = """{"schemaVersion":2,"sites":[]}"""
        directory.resolve(SITE_PROFILES_FILE_NAME).writeText(newer)

        withRepository(directory) { repository ->
            assertTrue(runCatching { repository.observeProfiles().first() }.isFailure)
            assertTrue(runCatching { repository.add(profile("booru.example")) }.isFailure)
        }

        assertEquals(newer, directory.resolve(SITE_PROFILES_FILE_NAME).readText())
    }

    private suspend fun withRepository(directory: File, block: suspend (DataStoreSiteProfileRepository) -> Unit) {
        val job = SupervisorJob()
        try {
            block(DataStoreSiteProfileRepository(directory, CoroutineScope(job + Dispatchers.IO)))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun profile(
        input: String,
        family: String = "GELBOORU_02",
        addedAt: Long = 10,
        headers: SiteHeaders = SiteHeaders(),
    ): SiteProfile {
        val address = (SiteAddress.parse(input) as SiteAddressParse.Valid).address
        return SiteProfile(
            sourceId = address.derivedSourceId,
            address = address,
            label = address.host,
            familyId = family,
            headers = headers,
            addedAtEpochMs = addedAt,
        )
    }
}
