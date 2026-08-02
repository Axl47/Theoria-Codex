package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAutoplayArchitectureTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate repository root")

    @Test
    fun `all five browsing surfaces retain the shared autoplay card`() {
        listOf(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt",
            "app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt",
            "app/src/main/java/com/theoriacodex/app/recents/RecentsScreen.kt",
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
        ).forEach { path ->
            assertTrue("$path must render SearchResultCard", "SearchResultCard(" in file(path).readText())
        }

        val codex = file(
            "app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt",
        ).readText()
        assertTrue("Codex cards require canonical stable keys", "key = { _, post ->" in codex)
    }

    @Test
    fun `shared card gates preparation before construction and keeps a stable player effect`() {
        val search = file(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
        ).readText()
        val feedMedia = file(
            "app/src/main/java/com/theoriacodex/app/search/FeedMediaComponents.kt",
        ).readText()
        assertTrue("Viewport must be clipped through actual layout coordinates", "boundsInWindow(clipBounds = true)" in search)
        assertTrue("Never-visible videos must return before player state is created", "if (!shouldOwnPlayer)" in feedMedia)
        assertTrue("Activation must be retained per media identity", "FeedPlayerActivationState()" in feedMedia)
        assertTrue("Player ownership must exclude lifecycle owner from its identity", "DisposableEffect(location, sourceKey)" in feedMedia)
        assertFalse(
            "Lifecycle identity changes must not recreate a key-stable player",
            "DisposableEffect(location, sourceKey, lifecycleOwner)" in feedMedia,
        )
        assertTrue("Video playback must use the bounded feed profile", "VideoPlaybackProfile.FEED_PREVIEW" in feedMedia)
        assertTrue("Ugoira playback must follow the same viewport/lifecycle gate", "isActive = playbackActive" in search)
        assertTrue("Animated images must stop outside the active viewport", "animatable?.stop()" in feedMedia)

        val ugoira = file(
            "app/src/main/java/com/theoriacodex/app/viewer/PixivUgoiraPlayer.kt",
        ).readText()
        assertTrue(
            "Never-visible Ugoira cards must not start archive download or decode",
            "playback != null || !isActive" in ugoira,
        )
    }

    @Test
    fun `Media3 factories and cache are application owned and request headers stay scoped`() {
        val application = file(
            "app/src/main/java/com/theoriacodex/app/TheoriaApplication.kt",
        ).readText()
        val infrastructure = file(
            "app/src/main/java/com/theoriacodex/app/viewer/VideoPlaybackInfrastructure.kt",
        ).readText()
        val exo = file(
            "app/src/main/java/com/theoriacodex/app/viewer/ExoVideoComponents.kt",
        ).readText()

        assertTrue("Application must own one lazy playback infrastructure", "videoPlaybackInfrastructure" in application && "by lazy" in application)
        assertTrue("Media3 cache must use a byte evictor", "LeastRecentlyUsedCacheEvictor(VIDEO_PLAYBACK_CACHE_MAX_BYTES)" in infrastructure)
        assertTrue(
            "Players must share immutable buffer policy but receive fresh load-control state",
            "internal object VideoLoadControlFactory" in infrastructure &&
                "VideoLoadControlFactory.create(profile)" in infrastructure,
        )
        assertFalse("Concurrent players must not share a state-owning load control", "private val previewLoadControl" in infrastructure)
        assertTrue("Protected headers must be copied per request", "val immutableHeaders = headers.toMap()" in infrastructure)
        assertFalse("Shared HTTP factory must never retain request headers", "setDefaultRequestProperties" in infrastructure)
        assertTrue("Duplicate cache writes must wait for the shared span", "CacheDataSource.FLAG_BLOCK_ON_CACHE" in infrastructure)
        assertTrue("ExoPlayer must resolve process-owned infrastructure", "videoPlaybackInfrastructure()" in exo)
        assertTrue("Each constructed player has exactly one prepare call", exo.split("prepare()").size - 1 == 1)
    }

    private fun file(path: String): File = File(repositoryRoot, path)
}
