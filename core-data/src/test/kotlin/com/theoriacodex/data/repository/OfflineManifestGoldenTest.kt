package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the version 3 offline manifest. v1.4.0 through v1.9.1 wrote it with Gson and later builds with kotlinx; both
 * produce these exact bytes. The generation folder is a random UUID, so it appears as a placeholder. Never regenerate
 * the golden to make a change pass: installed copies must keep loading unchanged.
 */
internal class OfflineManifestGoldenTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `new manifests keep the version 3 bytes`() = runTest {
        val directory = tempDir("manifest-golden-write")
        OfflineMediaStore(directory).save(source, source.media, "codex:b") { _, file -> file.writeText("bytes") }
        OfflineMediaStore(directory).retain(source.id, "codex:a")
        val folder = directory.listFiles().orEmpty().single()
        val generation = folder.listFiles().orEmpty().single(File::isDirectory)
        val actual = folder.resolve("manifest.json").readText().replace(jsonPath(generation), GENERATION)
        assertEquals(golden(), actual)
    }

    @Test
    fun `an installed version 3 manifest loads unchanged`() = runTest {
        val directory = tempDir("manifest-golden-read")
        val folder = File(directory, FOLDER)
        val generation = File(folder, "8d4c1d1e-5f43-4d0e-9a59-0f0b5e3f6a01").apply { mkdirs() }
        source.media.indices.forEach { File(generation, "$it.png").writeText("bytes") }
        folder.resolve("manifest.json").writeText(golden().replace(GENERATION, jsonPath(generation)))

        val store = OfflineMediaStore(directory)
        val loaded = requireNotNull(store.find(source.id))
        assertEquals(source.media.indices.map { File(generation, "$it.png").absolutePath }, loaded.media.map { it.localPath })
        assertEquals(loaded.media.first(), loaded.full)
        assertEquals(source, store.withoutOfflineLocations(loaded))
        assertEquals(mapOf("codex:a" to setOf(source.id), "codex:b" to setOf(source.id)), store.snapshot.value.postsByOwner)
    }

    // v3.json is what Gson and kotlinx both write; kotlinx-full.json is captured from current kotlinx output and also
    // sets video variants, a duration, a content hash and local paths on the source post, which v3.json never holds.
    @Test
    fun `the version 3 manifest goldens pin every stored key`() {
        val generation = "/offline/8d4c1d1e"
        val offlineMedia = source.media.mapIndexed { index, ref -> ref.copy(localPath = "$generation/$index.png") }
        val offline = source.copy(preview = offlineMedia[0], full = offlineMedia[0], media = offlineMedia)
        assertStoredFormat(
            golden("kotlinx-full.json") to fullManifest(),
            golden("v3.json").replace(GENERATION, generation) to OfflineManifest(
                3, PostStorageCodec.encode(offline), PostStorageCodec.encode(source), listOf("codex:a", "codex:b"), listOf(5L, 5L),
            ),
            // kotlinx-gallery.json: a listed gallery whose length is unknown, the only post that writes that flag.
            golden("kotlinx-gallery.json") to galleryManifest(),
        )
    }

    private fun galleryManifest(): OfflineManifest {
        val gallery = PostStorageCodec.encode(
            Post(
                id = PostId(SourceKey.NHENTAI, "7"),
                preview = ImageRef("https://example.test/7-thumb.jpg", null, "image/jpeg"),
                full = null, media = emptyList(), pageUrl = "https://example.test/g/7", width = null, height = null,
                canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
                title = "Gallery", galleryLengthUnknown = true,
            ),
        )
        return OfflineManifest(3, gallery, gallery, listOf("codex:a"), emptyList())
    }

    private fun fullManifest(): OfflineManifest {
        val variant = VideoVariant("https://example.test/42-720.mp4", height = 720, original = true, mime = "video/mp4")
        val full = source.copy(
            preview = source.preview.copy(localPath = "/offline/thumb.jpg"),
            full = source.full?.copy(localPath = "/offline/full.mp4"),
            media = source.media.map { it.copy(localPath = "/offline/${it.url?.substringAfterLast('/')}", videoVariants = listOf(variant)) },
            durationMs = 4_500L,
            contentHash = "0123456789abcdef0123456789abcdef",
        )
        return OfflineManifest(
            3,
            PostStorageCodec.encode(full.copy(preview = full.media[0], full = full.media[0])),
            PostStorageCodec.encode(full),
            listOf("codex:a"),
            listOf(5L, 5L),
        )
    }

    // Paths appear JSON-escaped, which matters for Windows separators.
    private fun jsonPath(file: File) = file.absolutePath.replace("\\", "\\\\")

    private fun golden(name: String = "v3.json"): String =
        checkNotNull(javaClass.classLoader.getResource("golden/offline-manifest/$name")) { "Missing golden" }.readText().trimEnd()

    private companion object {
        const val GENERATION = "\$GENERATION"
        // SHA-256 of "PIXIV:42", the folder every build derives from the post id.
        const val FOLDER = "085f8d98b58407021b860be805e79130750967159f5f9b05b3e1b8ea48ecf801"
        val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "7", "https://www.pixiv.net/users/7", "user:7")
        val source = Post(
            id = PostId(SourceKey.PIXIV, "42"),
            preview = ImageRef("https://example.test/42-thumb.jpg", null, "image/jpeg", progressiveUrls = listOf("https://example.test/42-small.jpg")),
            full = ImageRef(
                "https://example.test/42-p0.png", null, "image/png",
                videoVariants = listOf(VideoVariant("https://example.test/42-480.mp4", height = 480, mime = "video/mp4")),
            ),
            media = listOf(
                ImageRef("https://example.test/42-p0.png", null, "image/png", fallbackUrls = listOf("https://mirror.test/42-p0.png")),
                ImageRef("https://example.test/42-p1.png", null, "image/png", thumbnailUrl = "https://example.test/42-p1-thumb.jpg"),
            ),
            pageUrl = "https://www.pixiv.net/artworks/42",
            width = 1200,
            height = 1600,
            canonicalTags = listOf("cat"),
            rawTags = listOf("cat", "猫"),
            authorName = "Artist",
            createdAtEpochMs = 1_700_000_000_000L,
            title = "Cat & <friends>",
            creatorProfile = creator,
            durationMs = null,
            mediaCount = 2,
            taxonomy = listOf(PostTaxonomyTerm("cat", SearchFacet.TAG, "general", 12, translation = "neko")),
            creatorProfiles = listOf(creator),
        )
    }
}
