package com.theoriacodex.app.acceptance

import android.content.Context
import com.theoriacodex.app.media.FileBackedPostDownloadJobStore
import com.theoriacodex.app.media.PostDownloadBatch
import com.theoriacodex.app.media.PostDownloadItem
import com.theoriacodex.app.media.PostDownloadRequest
import com.theoriacodex.app.media.PostDownloadStatus
import com.theoriacodex.data.storage.withDataStoreScope
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.OfflinePendingJobStore
import com.theoriacodex.data.repository.ProfileDeletionPendingException
import com.theoriacodex.data.repository.ProfileDeletionService
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.READING_POSITIONS_FILE_NAME
import com.theoriacodex.data.repository.ReadingPosition
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Record files under R8 across the process restart: reading positions, the offline and download queues, and the
 * profile-removal journal this build writes, beside reading positions and a journal exactly as the Gson stores wrote
 * them. The write phase leaves both removals pending, as a crash during the library deletion would; a fresh owner in
 * the read phase must decode each journal and finish removing the same profiles. Room deletion itself is unit-tested.
 */
internal suspend fun verifyRecordFiles(context: Context, token: String, phase: String) {
    val directory = File(context.cacheDir, "release-contract-$token-records")
    val databaseName = "release-contract-$token-records.db"
    val database = TheoriaRoomDatabase.create(context, databaseName)
    try {
        withDataStoreScope { scope ->
            val settings = DataStoreSettingsRepository(directory.resolve("settings"), scope).apply { awaitReady() }
            if (phase == "write") writeRecordFiles(directory, settings, RoomCodexLikesRepository(database))
            else readRecordFiles(directory, settings, RoomCodexLikesRepository(database))
        }
    } finally {
        database.close()
        if (phase == "read") {
            context.deleteDatabase(databaseName)
            check(directory.deleteRecursively()) { "could not delete ${directory.path}" }
        }
    }
}

private suspend fun writeRecordFiles(directory: File, settings: DataStoreSettingsRepository, library: CodexLikesTransactions) {
    FileBackedReadingPositionRepository(directory).replace(positions)
    directory.resolve("shipped").apply { check(mkdirs()) { "could not create $path" } }.resolve(READING_POSITIONS_FILE_NAME).writeText(SHIPPED_POSITIONS)
    OfflinePendingJobStore(directory.resolve("offline_jobs.json")).save(offlineRequests)
    FileBackedPostDownloadJobStore(directory.resolve("post_download_jobs.json")).save(downloads)

    val current = settings.addRecommendationProfile(CURRENT_PROFILE)
    val shipped = settings.addRecommendationProfile(SHIPPED_PROFILE)
    val interrupted = object : CodexLikesTransactions by library {
        override suspend fun removeProfileLibrary(profileId: String) = throw IOException("Process stopped mid-removal")
    }
    val removal = ProfileDeletionService(settings, interrupted, directory.resolve(CURRENT_JOURNAL), ProfileLibraryWriteGuard())
    val pending = try {
        removal.removeProfile(current.profileId)
        false
    } catch (_: ProfileDeletionPendingException) {
        true
    }
    check(pending) { "profile deletion was not left pending" }
    check(directory.resolve(CURRENT_JOURNAL).isFile) { "current journal missing at ${directory.resolve(CURRENT_JOURNAL)}" }
    directory.resolve(SHIPPED_JOURNAL).writeText("""{"version":1,"profileId":"${shipped.profileId}"}""")
}

private suspend fun readRecordFiles(directory: File, settings: DataStoreSettingsRepository, library: CodexLikesTransactions) {
    val currentPositions = FileBackedReadingPositionRepository(directory).snapshot()
    check(currentPositions == positions) { "current reading positions were $currentPositions" }
    val shippedPositions = FileBackedReadingPositionRepository(directory.resolve("shipped")).snapshot()
    check(shippedPositions == positions) { "shipped reading positions were $shippedPositions" }
    val wire = Json.parseToJsonElement(directory.resolve(READING_POSITIONS_FILE_NAME).readText()).jsonObject
    check(wire.keys == setOf("schemaVersion", "positions")) { "reading positions keys were ${wire.keys}" }
    check(wire.getValue("positions").jsonArray.first().jsonObject.keys ==
        setOf("source", "postId", "section", "mediaNumber", "lastViewedAt")) { "reading position keys were ${wire.getValue("positions").jsonArray.first().jsonObject.keys}" }
    val offlineJobs = OfflinePendingJobStore(directory.resolve("offline_jobs.json")).load()
    check(offlineJobs == offlineRequests) { "offline jobs were $offlineJobs" }
    val restored = FileBackedPostDownloadJobStore(directory.resolve("post_download_jobs.json")).load().single()
    check(restored.title == downloads.single().title) { "restored download title was ${restored.title}" }
    check(restored.items.single().let { it.request.post.id == downloadPost.id && it.request.marker == "marker & <1>" }) { "restored download items were ${restored.items}" }

    val profiles = settings.observeSettings().first().recommendationProfiles
    val removing = listOf(CURRENT_PROFILE, SHIPPED_PROFILE).map { name -> profiles.single { it.name == name }.profileId }
    val journal = Json.parseToJsonElement(directory.resolve(CURRENT_JOURNAL).readText()).jsonObject
    check(journal.keys == setOf("version", "profileId") && journal.getValue("profileId").jsonPrimitive.content == removing[0]) { "deletion journal was $journal" }
    listOf(CURRENT_JOURNAL, SHIPPED_JOURNAL).forEach { name ->
        val owner = ProfileDeletionService(settings, library, directory.resolve(name), ProfileLibraryWriteGuard())
        check(owner.recoverPendingDeletion() && !directory.resolve(name).exists()) { "recovery of $name did not complete or left the journal" }
    }
    val remaining = settings.observeSettings().first().recommendationProfiles
    check(remaining.none { it.profileId in removing }) { "recommendation profiles were $remaining" }
}

private const val CURRENT_PROFILE = "Removed & <current>"
private const val SHIPPED_PROFILE = "Removed by a Gson build"
private const val CURRENT_JOURNAL = "pending_profile_deletion.json"
private const val SHIPPED_JOURNAL = "shipped_profile_deletion.json"

private val positions = listOf(
    ReadingPosition(PostId(SourceKey.parseOrThrow("custom_safebooru_org"), "tom & jerry's <cut>=1"), RecentPostSection.CODEX, 12, 1_700_000_100_000L),
    ReadingPosition(PostId(SourceKey.PIXIV, "12345"), RecentPostSection.WATCHED, 3, 1_700_000_000_000L),
)

// What the Gson store wrote for [positions], HTML escapes included.
private const val SHIPPED_POSITIONS = """{"schemaVersion":1,"positions":[{"source":"custom_safebooru_org",""" +
    """"postId":"tom & jerry's <cut>=1","section":"CODEX","mediaNumber":12,""" +
    """"lastViewedAt":1700000100000},{"source":"PIXIV","postId":"12345","section":"WATCHED","mediaNumber":3,""" +
    """"lastViewedAt":1700000000000}]}"""

private val offlineRequests = mapOf("codex:a & b" to listOf(PostId(SourceKey.PIXIV, "1"), PostId(SourceKey.GELBOORU, "x<y>")))

private val downloadPost = Post(PostId(SourceKey.PIXIV, "100"), ImageRef(null, null, null), null, pageUrl = null,
    width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null,
    createdAtEpochMs = null, title = "Tom & Jerry's <cut>")

private val downloads = listOf(PostDownloadBatch(1, "Codex & <friends>", listOf(
    PostDownloadItem(2, PostDownloadRequest(downloadPost, marker = "marker & <1>"), PostDownloadStatus.SAVED),
)))
