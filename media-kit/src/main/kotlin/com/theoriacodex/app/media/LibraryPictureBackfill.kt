package com.theoriacodex.app.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hashes the pictures of liked and saved posts that have no record yet ([VisualSignatureRepository]), so a repost of
 * an older Library post on another site is recognised too. A post saved from a listing already brought its hash; this
 * covers the Library from before and posts saved without one. It runs only while the device charges, a batch of
 * previews at a time, and stops when it is unplugged. A preview that fails to load is skipped until the app restarts.
 */
class LibraryPictureBackfill(
    private val signatures: VisualSignatureRepository,
    private val hash: suspend (Post) -> PictureHashResult,
    private val charging: Flow<Boolean>,
) {
    suspend fun run() {
        val unavailable = HashSet<PostId>()
        charging.distinctUntilChanged().collectLatest { plugged ->
            while (plugged) {
                val batch = signatures.libraryPostsWithoutSignature(unavailable.size + BATCH).filterNot { it.id in unavailable }
                if (batch.isEmpty()) break
                coroutineScope { batch.map { post -> async { post to hash(post) } }.awaitAll() }.forEach { (post, result) ->
                    when (result) {
                        is PictureHashResult.Hashed -> signatures.put(post.id, result.hash)
                        PictureHashResult.Unhashable -> signatures.put(post.id, null)
                        PictureHashResult.Unavailable -> unavailable += post.id
                    }
                }
            }
        }
    }

    private companion object {
        const val BATCH = 32
    }
}

/** Whether the device is plugged in, now and on every change. */
fun Context.chargingState(): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.isPlugged())
        }
    }
    // The battery broadcast is sticky, so registering answers with the current state at once.
    registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let { trySend(it.isPlugged()) }
    awaitClose { unregisterReceiver(receiver) }
}.distinctUntilChanged()

private fun Intent.isPlugged(): Boolean = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
