package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTagEntryRecordTest {
    @Test
    fun `a record written before or groups reads as one saved tag`() {
        val record = FavoriteTagEntryRecord(source = "GELBOORU", tag = "Blue Hair")

        assertEquals(FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"), record.toDomainOrNull())
    }

    @Test
    fun `a saved group keeps its first tag readable by older builds and round trips`() {
        val group = FavoriteTagEntry(SourceKey.GELBOORU, listOf("blue_hair", "red_hair"), "Hair colors")

        val record = FavoriteTagEntryRecord.fromDomain(group)

        assertEquals("blue_hair", record.tag)
        assertEquals(group, record.toDomainOrNull())
        assertNull(FavoriteTagEntryRecord.fromDomain(FavoriteTagEntry(SourceKey.PIXIV, "cat")).tags)
    }

    @Test
    fun `stored favorite tags keep their spelling, which recommendation normalization would change (ADR 0003)`() {
        val booru = FavoriteTagEntryRecord.fromDomain(
            FavoriteTagEntry(SourceKey.GELBOORU, listOf(" Blue  Hair ", "Jojo&#039;s Bizarre_Adventure")),
        )
        val spaced = FavoriteTagEntryRecord.fromDomain(FavoriteTagEntry(SourceKey.HITOMI, " Full_Color "))

        assertEquals(listOf("blue_hair", "jojo's_bizarre_adventure"), booru.tags)
        assertEquals("Full_Color", spaced.tag)
        assertEquals(FavoriteTagEntry(SourceKey.PIXIV, "Ｂｌｕｅ_Hair"), FavoriteTagEntryRecord(source = "PIXIV", tag = " Ｂｌｕｅ_Hair ").toDomainOrNull())
    }
}
