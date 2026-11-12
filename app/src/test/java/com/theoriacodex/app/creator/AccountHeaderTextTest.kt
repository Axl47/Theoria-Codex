package com.theoriacodex.app.creator

import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.SourceKey
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountHeaderTextTest {
    private fun text(details: AccountDetails?) =
        accountHeaderText(SourceKey.GELBOORU, details, ZoneOffset.UTC, Locale.US)

    @Test
    fun `no details leaves only the source`() {
        assertEquals(AccountHeaderText("Gelbooru", null), text(null))
    }

    @Test
    fun `only known counts are listed and long ones are compacted`() {
        assertEquals("Gelbooru · 312 uploads · 1.4k favorites", text(AccountDetails(uploadCount = 312, favoriteCount = 1_400)).stats)
        assertEquals("Gelbooru · 1 upload", text(AccountDetails(uploadCount = 1)).stats)
        assertEquals("Gelbooru", text(AccountDetails(bio = "hi")).stats)
    }

    @Test
    fun `a zero favorites count is not shown`() {
        assertEquals("Gelbooru · 12 uploads", text(AccountDetails(uploadCount = 12, favoriteCount = 0)).stats)
    }

    @Test
    fun `joined date and followers fill a second line only when present`() {
        val joined = 1_615_000_000_000L
        assertEquals("Joined Mar 2021 · 2.1k followers", text(AccountDetails(joinedAtEpochMs = joined, followerCount = 2_100)).secondary)
        assertEquals("1 follower", text(AccountDetails(followerCount = 1)).secondary)
        assertNull(text(AccountDetails(uploadCount = 4)).secondary)
    }
}
