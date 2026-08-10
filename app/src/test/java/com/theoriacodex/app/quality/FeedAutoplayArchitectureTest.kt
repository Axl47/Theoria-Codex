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

        val sharedGrid = file(
            "app/src/main/java/com/theoriacodex/app/ui/components/PostStaggeredGrid.kt",
        ).readText()
        assertTrue("Shared feed cards require canonical stable keys", "key = { _, post ->" in sharedGrid)
    }

    @Test
    fun `shared card gates preparation before construction and keeps a stable player effect`() {
        val search = file(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
        ).readText()
        val feedMedia = file(
            "app/src/main/java/com/theoriacodex/app/search/FeedMediaComponents.kt",
        ).readText()
        val feedPool = file(
            "app/src/main/java/com/theoriacodex/app/viewer/FeedPreviewPlayerPool.kt",
        ).readText()
        assertTrue("Viewport must be clipped through actual layout coordinates", "boundsInWindow(clipBounds = true)" in search)
        assertTrue(
            "Transiently visible videos must return before a player is leased",
            "if (!shouldLeasePlayer)" in feedMedia,
        )
        assertTrue("Stable visibility must gate player leasing", "delay(FEED_PLAYER_ACTIVATION_DELAY_MS)" in feedMedia)
        assertTrue(
            "Only visibly presented cards may hold a player lease",
            "DisposableEffect(location, sourceKey, isActive)" in feedMedia,
        )
        assertFalse(
            "Lifecycle identity changes must not recreate a key-stable player",
            "DisposableEffect(location, sourceKey, lifecycleOwner)" in feedMedia,
        )
        assertTrue("Video playback must use the bounded feed profile", "VideoPlaybackProfile.FEED_PREVIEW" in feedPool)
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
        val feedPool = file(
            "app/src/main/java/com/theoriacodex/app/viewer/FeedPreviewPlayerPool.kt",
        ).readText()
        val feedMedia = file(
            "app/src/main/java/com/theoriacodex/app/search/FeedMediaComponents.kt",
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
        assertTrue("Feed players must be application-owned reusable leases", "feedPreviewPlayerPool" in infrastructure)
        assertTrue("Muted previews must not select audio decoders", "setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)" in feedPool)
        assertTrue("Lazy-grid media views must opt into reuse", "onReset =" in feedMedia)
        assertFalse("Card disposal must not synchronously release players", "player.release()" in feedMedia)
        assertTrue("Excess idle decoders must cool without capping visible players", "PREVIEW_PLAYER_COOL" in feedPool)
        assertTrue("Cooled players must discard old media resources", "clearMediaItems()" in feedPool)
        assertTrue("Rebinding must stop the previous media before preparing a new identity", "PREVIEW_PLAYER_REBIND" in feedPool)
        assertTrue("Visible decoder startup must use the cancellation-aware prepare queue", "pendingPrepares" in feedPool)
        assertTrue("Decoder startup must be paced instead of bursting in one frame", "FEED_PREVIEW_PREPARE_SPACING_MS" in feedPool)
        assertTrue("Device traces must expose active player ownership", "PREVIEW_ACTIVE_PLAYERS" in feedPool)
        assertTrue("Slow release must be paced outside route disposal", "FEED_PREVIEW_RELEASE_SPACING_MS" in feedPool)
    }

    private fun file(path: String): File = File(repositoryRoot, path)
}
