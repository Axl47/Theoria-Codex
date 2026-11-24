package com.theoriacodex.app.series

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.theoriacodex.app.MainActivity
import com.theoriacodex.app.R
import com.theoriacodex.data.repository.DrainProgress
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.MutableStateFlow

private const val NEW_CHAPTERS_CHANNEL = "new_chapters"
private const val NEW_CHAPTERS_GROUP = "new_chapters"

/** Every series' notification has this id and its series as the tag, so a later one for the same series replaces it. */
internal const val SERIES_NOTIFICATION_ID = 1
private const val SUMMARY_NOTIFICATION_ID = 2

/**
 * Posts [update]'s local notification, and the group's summary once two series have one showing. Tapping it opens
 * that series' page. Without the notification permission nothing is posted; the new entries are stored either way.
 * Both kinds share one channel, kept under its first id so the user's choices for it carry over.
 */
internal fun notifySeriesUpdate(context: Context, update: SeriesUpdate) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(NotificationChannel(NEW_CHAPTERS_CHANNEL, "New chapters and episodes", NotificationManager.IMPORTANCE_DEFAULT))
    fun notification() = Notification.Builder(context, NEW_CHAPTERS_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_series)
        .setGroup(NEW_CHAPTERS_GROUP)
        .setAutoCancel(true)
    val tag = update.series.id.toString()
    val open = Intent(Intent.ACTION_VIEW, seriesUri(update.series.id), context, SeriesNotificationActivity::class.java)
    manager.notify(
        tag,
        SERIES_NOTIFICATION_ID,
        notification()
            .setContentTitle(update.series.title)
            .setContentText(newEntriesText(update))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build(),
    )
    // Counted with this one by name: the system may not list a notification the moment it is posted.
    val showing = manager.activeNotifications.filter { it.id == SERIES_NOTIFICATION_ID }.mapTo(mutableSetOf(tag)) { it.tag }
    if (showing.size > 1) {
        // The summary names no series: tapping it only brings the app forward.
        val app = PendingIntent.getActivity(context, 0, Intent(context, SeriesNotificationActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SUMMARY_NOTIFICATION_ID, notification().setGroupSummary(true).setContentIntent(app).build())
    }
}

private const val DOWNLOADS_CHANNEL = "chapter_downloads"

/** The download queue's one ongoing notification; a failed download's summary replaces the last one. */
internal const val DOWNLOAD_PROGRESS_NOTIFICATION_ID = 3
private const val DOWNLOADS_FAILED_NOTIFICATION_ID = 4

/**
 * The download queue's ongoing notification: the series and chapter being fetched, which of the chapters taken on so
 * far it is, and its pages as progress. Tapping it opens that series' page. [progress] is null before the first
 * chapter starts.
 */
internal fun downloadProgressNotification(context: Context, progress: DrainProgress?): Notification {
    val builder = downloadNotification(context).setOngoing(true).setOnlyAlertOnce(true)
    if (progress == null) return builder.setContentTitle("Downloading chapters").setProgress(0, 0, true).build()
    val open = Intent(Intent.ACTION_VIEW, seriesUri(progress.seriesId), context, SeriesNotificationActivity::class.java)
    return builder
        .setContentTitle(progress.series)
        .setContentText(downloadProgressText(progress))
        .setProgress(progress.pageCount, progress.pages, progress.pageCount == 0)
        .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
}

/** "Ch. 12 · 2 of 5". */
internal fun downloadProgressText(progress: DrainProgress): String =
    listOf(progress.chapter, "${progress.number} of ${progress.of}").filter(String::isNotBlank).joinToString(" · ")

/** Shows the queue's notification outside a user-initiated job, which shows its own. Without the permission, nothing shows. */
internal fun postDownloadProgress(context: Context, notification: Notification) {
    context.getSystemService(NotificationManager::class.java).notify(DOWNLOAD_PROGRESS_NOTIFICATION_ID, notification)
}

internal fun cancelDownloadProgress(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(DOWNLOAD_PROGRESS_NOTIFICATION_ID)
}

/** Says how many chapters a drain gave up on; they show as not downloaded again. */
internal fun notifyDownloadsFailed(context: Context, failed: Int) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    val app = PendingIntent.getActivity(context, 0, Intent(context, SeriesNotificationActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    manager.notify(
        DOWNLOADS_FAILED_NOTIFICATION_ID,
        downloadNotification(context).setContentTitle(downloadsFailedText(failed)).setContentIntent(app).setAutoCancel(true).build(),
    )
}

internal fun downloadsFailedText(failed: Int): String =
    if (failed == 1) "1 chapter could not be downloaded" else "$failed chapters could not be downloaded"

private fun downloadNotification(context: Context): Notification.Builder {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(DOWNLOADS_CHANNEL, "Chapter downloads", NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(context, DOWNLOADS_CHANNEL).setSmallIcon(R.drawable.ic_notification_series)
}

/**
 * Where a new-chapters or download notification lands: it names the series for the running app and brings the app
 * forward. It is not exported, so only this app's notifications reach it, and [opened] is the only way to a series from
 * outside the app's own screens: a link another app sends cannot name one.
 */
class SeriesNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened.value = intent.data?.let(::seriesIdOfUri)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    internal companion object {
        /** The series a tapped notification named, until the app has opened its page. */
        val opened = MutableStateFlow<SeriesId?>(null)
    }
}

/** The new entry's name, or how many there are, as chapters or episodes by the series' kind. */
internal fun newEntriesText(update: SeriesUpdate): String =
    update.newEntries.singleOrNull()?.name ?: update.series.id.source.seriesKind.words.new(update.newEntries.size)

/** The address a notification's intent carries to [SeriesNotificationActivity]; [seriesIdOfUri] reads it back. */
internal fun seriesUri(id: SeriesId): Uri = Uri.Builder().scheme(SERIES_URI_SCHEME).authority(SERIES_URI_HOST)
    .appendQueryParameter("source", id.source.id).appendQueryParameter("address", id.address).build()

/** The series a [seriesUri] names; null for any other address. */
internal fun seriesIdOfUri(uri: Uri): SeriesId? {
    if (uri.scheme != SERIES_URI_SCHEME || uri.authority != SERIES_URI_HOST) return null
    val source = uri.getQueryParameter("source")?.let(SourceKey::parse) ?: return null
    return SeriesId(source, uri.getQueryParameter("address") ?: return null)
}

private const val SERIES_URI_SCHEME = "theoriacodex"
private const val SERIES_URI_HOST = "series"
