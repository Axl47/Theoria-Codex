package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.theoriacodex.data.storage.ImageRefStorageRecord
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.VideoVariantRecord
import com.theoriacodex.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaPreferencesStorageTest {
    @Test fun `old settings retain safe defaults and new settings round trip`() {
        val gson = Gson()
        val old = gson.fromJson("{}", LegacySettingsStoreRecord::class.java).toDomain()
        assertEquals(VideoQuality.AUTO, old.viewer.videoQuality)
        assertEquals(VideoQuality.BEST, old.cache.downloadQuality)
        assertFalse(old.cache.downloadsOverRoaming)
        val expected = AppSettings(viewer = ViewerSettings(videoQuality = VideoQuality.DATA_SAVER),
            cache = CacheSettings(downloadQuality = VideoQuality.AUTO, downloadsOverMetered = false))
        val stored = gson.fromJson(gson.toJson(LegacySettingsStoreRecord.fromDomain(expected)),
            LegacySettingsStoreRecord::class.java).toDomain()
        assertEquals(expected.viewer, stored.viewer)
        assertEquals(expected.cache, stored.cache)
    }

    @Test fun `video renditions survive both full and gallery storage without extra pages`() {
        val variants = listOf(VideoVariantRecord("https://media/720.mp4", 720),
            VideoVariantRecord("https://media/original.mp4", original = true))
        val record = PostStorageRecord(source = "IWARA", sourcePostId = "42", fullUrl = "https://media/original.mp4",
            fullMime = "video/mp4", fullVideoVariants = variants,
            media = listOf(ImageRefStorageRecord(url = "https://media/original.mp4", mime = "video/mp4", videoVariants = variants)))
        val post = requireNotNull(PostStorageCodec.decode(record))
        val decoded = PostStorageCodec.decode(PostStorageCodec.encode(post))
        assertEquals(post, decoded)
        val sparse = post.copy(full = post.full?.copy(videoVariants = emptyList()), media = emptyList())
        assertEquals(post.full?.videoVariants, mergeSparsePost(post, sparse).full?.videoVariants)
        assertEquals(1, post.media.size)
        assertEquals(2, requireNotNull(decoded?.full).videoVariants.size)
    }
}
