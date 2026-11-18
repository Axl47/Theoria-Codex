package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.DurableJson
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ProfileDeletionPendingException(cause: Exception) : IllegalStateException(
    "Profile removal paused. Retry removal or reopen the app to finish safely.", cause,
)

/** A small durable intent bridges one atomic Room library deletion and its Settings removal. */
class ProfileDeletionService(
    private val settings: SettingsRepository,
    private val library: CodexLikesTransactions,
    private val journalFile: File,
    private val writeGuard: ProfileLibraryWriteGuard,
    private val fileStore: AtomicJsonFileStore = AtomicJsonFileStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    fun isRemoving(profileId: String): Boolean = writeGuard.isRetired(profileId)

    suspend fun removeProfile(profileId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val pending = journalFile.exists()
            if (pending) check(readPending() == profileId) { "Finish the pending profile removal first" }
            else {
                val current = settings.observeSettings().first()
                require(current.recommendationProfiles.size > 1 && current.recommendationProfiles.any { it.profileId == profileId }) {
                    "This profile cannot be removed"
                }
            }
            // Once the durable intent is admitted, caller departure cannot skip its finish or guard.
            withContext(NonCancellable) {
                if (!pending) {
                    fileStore.writeIntent(journalFile, ProfileDeletionJournal(1, profileId), ProfileDeletionJournal.serializer(),
                        maxBytes = 4_096)
                }
                finish(profileId)
            }
        }
    }

    suspend fun recoverPendingDeletion(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            if (!journalFile.exists()) return@withLock false
            finish(readPending())
            true
        }
    }

    private suspend fun finish(profileId: String) = withContext(NonCancellable) {
        writeGuard.retire(profileId)
        try {
            val current = settings.observeSettings().first()
            if (current.activeProfileId == profileId) {
                val replacement = current.recommendationProfiles.firstOrNull { it.profileId != profileId }
                    ?: error("A remaining profile is required")
                settings.setActiveProfile(replacement.profileId)
            }
            library.removeProfileLibrary(profileId)
            settings.removeRecommendationProfile(profileId)
            check(settings.observeSettings().first().recommendationProfiles.none { it.profileId == profileId }) {
                "The removed profile was not committed"
            }
            check(journalFile.delete() || !journalFile.exists()) { "Could not finish the profile removal journal" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProfileDeletionPendingException(failure)
        }
    }

    /** Critical intent reads fail closed; corruption must never erase a partially completed operation. */
    private fun readPending(): String {
        require(journalFile.length() in 1..4_096L) { "Invalid profile removal journal size" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(journalFile.readBytes())).toString()
        val record = DurableJson.decodeFromString(ProfileDeletionJournal.serializer(), text)
        require(record.version == 1) { "Unsupported profile removal journal" }
        return record.profileId.also { require(it.isNotBlank() && it.length <= 500) }
    }
}

/** pending_profile_deletion.json: both keys are required, so a damaged journal fails instead of guessing. */
@Serializable
internal data class ProfileDeletionJournal(
    @SerialName("version")
    val version: Int,
    @SerialName("profileId")
    val profileId: String,
)
