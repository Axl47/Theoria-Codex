package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteProfileRecordsTest {
    private val gson = Gson()

    @Test
    fun `records round trip every profile field through JSON without credentials`() {
        val original = profile("https://booru.example:8443/board", familyId = "DANBOORU").copy(
            searchTermLimit = 2,
            headers = SiteHeaders(userAgent = "Theoria/1", referer = "https://booru.example/"),
            credentialsRequired = true,
            verifiedAtEpochMs = 20L,
        )

        val json = gson.toJson(SiteProfileRecord.fromDomain(original))
        val decoded = gson.fromJson(json, SiteProfileRecord::class.java).toDomainOrNull()

        assertEquals(original, decoded)
        assertFalse(json.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun `a family from a newer app version is kept raw`() {
        val stored = SiteProfileRecord.fromDomain(profile("safebooru.org", familyId = "MOEBOORU"))

        val decoded = requireNotNull(stored.toDomainOrNull())

        assertEquals("MOEBOORU", decoded.familyId)
        assertNull(decoded.family)
    }

    @Test
    fun `invalid records decode to null`() {
        val valid = SiteProfileRecord.fromDomain(profile("safebooru.org"))

        listOf(
            valid.copy(sourceId = "PIXIV"),
            valid.copy(sourceId = null),
            valid.copy(baseUrl = "http://safebooru.org"),
            valid.copy(label = ""),
            valid.copy(family = null),
            valid.copy(addedAt = null),
            valid.copy(userAgent = "bad\nheader"),
            gson.fromJson("{}", SiteProfileRecord::class.java),
        ).forEach { record -> assertNull(record.toString(), record.toDomainOrNull()) }
    }

    @Test
    fun `in-memory repository enforces identity, the site limit and restore semantics`() = runTest {
        val repository = InMemorySiteProfileRepository()
        val first = profile("safebooru.org")

        assertEquals(SiteProfileAddResult.Added, repository.add(first))
        assertEquals(SiteProfileAddResult.Duplicate(first.sourceId), repository.add(first.copy(label = "Again")))
        assertTrue(repository.update(first.sourceId) { it.copy(label = "Safe") })
        assertFalse(repository.update("custom_missing_example") { it })
        assertTrue(runCatching { repository.update(first.sourceId) { profile("other.example") } }.isFailure)

        (1 until SiteProfile.MAX_CUSTOM_SITES).forEach { index -> repository.add(profile("site$index.example")) }
        assertEquals(SiteProfileAddResult.LimitReached, repository.add(profile("one-more.example")))

        val conflicting = first.copy(familyId = "DANBOORU")
        val merge = repository.mergeRestored(listOf(conflicting, profile("restored.example")))
        assertEquals(SiteProfileMergeResult(added = 1, keptExisting = listOf(first.sourceId)), merge)
        val stored = repository.observeProfiles().first()
        assertEquals(SiteProfile.MAX_CUSTOM_SITES + 1, stored.size)
        assertEquals("Safe", stored.first().label)

        assertTrue(repository.remove(first.sourceId))
        assertFalse(repository.remove(first.sourceId))
    }

    private fun profile(input: String, familyId: String = "GELBOORU_02"): SiteProfile {
        val address = (SiteAddress.parse(input) as SiteAddressParse.Valid).address
        return SiteProfile(
            sourceId = address.derivedSourceId,
            address = address,
            label = "Site",
            familyId = familyId,
            addedAtEpochMs = 10L,
        )
    }
}
