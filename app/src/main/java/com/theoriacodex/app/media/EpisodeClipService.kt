package com.theoriacodex.app.media

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import com.theoriacodex.app.MainActivity
import com.theoriacodex.app.R
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EpisodeClipService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(CLIP_NOTIFICATION_ID, clipNotification(this, null, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        if (watching?.isActive != true) watching = scope.launch { watchClips() }
        return START_NOT_STICKY
    }

    private suspend fun watchClips() {
        val downloads = (application as TheoriaAppContainerOwner).awaitAppContainer().features.postDownloads
        var wasActive = emptySet<Long>()
        var shown: Pair<Long, Int?>? = null
        while (true) {
            val seen = latestStartId
            withTimeoutOrNull(SUBMIT_GRACE_MS) { downloads.batches.first { it.activeClips().isNotEmpty() } }
            downloads.batches.transformWhile { batches -> emit(batches); batches.activeClips().isNotEmpty() }.collect { batches ->
                val clips = batches.clipItems()
                clips.filter { it.id in wasActive && !it.active }.forEach { notifyClipOutcome(downloads, it) }
                wasActive = clips.filter { it.active }.mapTo(mutableSetOf()) { it.id }
                val clip = batches.activeClips().firstOrNull() ?: return@collect
                if (shown == clip.id to clip.encodedPercent) return@collect
                shown = clip.id to clip.encodedPercent
                getSystemService(NotificationManager::class.java)
                    .notify(CLIP_NOTIFICATION_ID, clipNotification(this, clip.request.post.title, clip.encodedPercent))
            }
            if (latestStartId != seen || downloads.batches.value.activeClips().isNotEmpty()) continue
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(seen)
            return
        }
    }

    private suspend fun notifyClipOutcome(downloads: PostDownloads, item: PostDownloadItem) {
        when (item.status) {
            PostDownloadStatus.SAVED -> downloads.localFile(item)?.let { file -> notifyClipSaved(this, item, file) }
            PostDownloadStatus.FAILED -> notifyClipFailed(this, item)
            else -> Unit
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, EpisodeClipService::class.java))
            } catch (_: ForegroundServiceStartNotAllowedException) {
                Unit
            }
        }
    }
}

private fun clipNotificationBuilder(context: Context): Notification.Builder {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CLIPS_CHANNEL, "Clips", NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(context, CLIPS_CHANNEL).setSmallIcon(R.drawable.ic_notification_series)
}

private fun clipNotification(context: Context, title: String?, percent: Int?): Notification =
    clipNotificationBuilder(context)
        .setContentTitle(title?.takeIf(String::isNotBlank) ?: "Clipping")
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setProgress(PERCENT, percent ?: 0, percent == null)
        .build()

private fun notifyClipSaved(context: Context, item: PostDownloadItem, file: DownloadedPostFile) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    val share = PendingIntent.getActivity(context, item.id.toInt(), shareDownloadedPostFileIntent(file),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify(CLIP_OUTCOME_TAG, item.id.toInt(), clipNotificationBuilder(context)
        .setContentTitle("Clip saved")
        .setContentText(item.request.post.title)
        .setContentIntent(share)
        .setAutoCancel(true)
        .build())
}

private fun notifyClipFailed(context: Context, item: PostDownloadItem) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    manager.notify(CLIP_OUTCOME_TAG, item.id.toInt(), clipNotificationBuilder(context)
        .setContentTitle("Clip failed")
        .setContentText(item.message ?: item.request.post.title)
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true)
        .build())
}

private const val CLIPS_CHANNEL = "episode-clips"
private const val CLIP_NOTIFICATION_ID = 5_800
private const val CLIP_OUTCOME_TAG = "episode-clip"
private const val PERCENT = 100
private const val SUBMIT_GRACE_MS = 10_000L

private fun List<PostDownloadBatch>.clipItems(): List<PostDownloadItem> = flatMap { it.items }.filter { it.request.clip != null }
