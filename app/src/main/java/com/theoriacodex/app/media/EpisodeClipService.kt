package com.theoriacodex.app.media

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EpisodeClipService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(CLIP_NOTIFICATION_ID, clipNotification(this, null, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        if (watching?.isActive != true) watching = scope.launch {
            val downloads = (application as TheoriaAppContainerOwner).awaitAppContainer().features.postDownloads
            val clipped = mutableSetOf<Long>()
            val progress = downloads.batches.map { batches -> batches.activeClips().onEach { clipped += it.id }.firstOrNull() }
            withTimeoutOrNull(SUBMIT_GRACE_MS) { progress.first { it != null } }
            progress.distinctUntilChanged().takeWhile { it != null }.collect { clip ->
                getSystemService(NotificationManager::class.java)
                    .notify(CLIP_NOTIFICATION_ID, clipNotification(this@EpisodeClipService, clip?.request?.post?.title, clip?.encodedPercent))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            downloads.batches.value.flatMap { it.items }.lastOrNull { it.id in clipped && it.status == PostDownloadStatus.SAVED }
                ?.let { saved -> downloads.localFile(saved)?.let { file -> notifyClipSaved(this@EpisodeClipService, saved, file) } }
            stopSelf()
        }
        return START_NOT_STICKY
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
            context.startForegroundService(Intent(context, EpisodeClipService::class.java))
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
    manager.notify(CLIP_SAVED_NOTIFICATION_ID, clipNotificationBuilder(context)
        .setContentTitle("Clip saved")
        .setContentText(item.request.post.title)
        .setContentIntent(share)
        .setAutoCancel(true)
        .build())
}

private const val CLIPS_CHANNEL = "episode-clips"
private const val CLIP_NOTIFICATION_ID = 5_800
private const val CLIP_SAVED_NOTIFICATION_ID = 5_801
private const val PERCENT = 100
private const val SUBMIT_GRACE_MS = 10_000L
