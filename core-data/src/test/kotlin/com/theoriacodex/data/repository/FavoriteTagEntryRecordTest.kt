package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTagEntryRecordTest {
    @Test
    fun `a record written before or groups reads as one saved tag`() {
        val record = LegacyFavoriteTagEntryRecord(source = "GELBOORU", tag = "Blue Hair")

        assertEquals(FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"), record.toDomainOrNull())
    }

    @Test
    fun `a saved group keeps its first tag readable by older builds and round trips`() {
        val group = FavoriteTagEntry(SourceKey.GELBOORU, listOf("blue_hair", "red_hair"))

        val record = LegacyFavoriteTagEntryRecord.fromDomain(group)

        assertEquals("blue_hair", record.tag)
        assertEquals(group, record.toDomainOrNull())
        assertNull(LegacyFavoriteTagEntryRecord.fromDomain(FavoriteTagEntry(SourceKey.PIXIV, "cat")).tags)
    }
}
