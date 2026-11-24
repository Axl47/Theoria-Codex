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
import com.theoriacodex.data.repository.ChapterName
import com.theoriacodex.data.repository.DrainProgress
import com.theoriacodex.data.repository.QueuedChapter
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
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
 * that series' page; when it announces one entry, its Read or Watch action opens that entry in the Viewer. Without the notification permission nothing is posted; the new entries are stored either way.
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
    fun opening(entry: String?) = PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, seriesUri(update.series.id, entry), context, SeriesNotificationActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = notification()
        .setContentTitle(update.series.title)
        .setContentText(newEntriesText(update))
        .setContentIntent(opening(entry = null))
    update.newEntries.singleOrNull()?.let { entry ->
        val action = update.series.id.source.seriesKind.words.open
        builder.addAction(Notification.Action.Builder(null, action, opening(entry.address)).build())
    }
    manager.notify(tag, SERIES_NOTIFICATION_ID, builder.build())
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

/** Each lane's chapters waiting for their extension, under its lane's tag. */
private const val DOWNLOADS_WAITING_NOTIFICATION_ID = 5

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

/** Says which chapters a drain gave up on; they show as not downloaded again. */
internal fun notifyDownloadsFailed(context: Context, failed: List<ChapterName>) {
    postChaptersNotice(context, null, DOWNLOADS_FAILED_NOTIFICATION_ID, failedNotice(failed))
}

/**
 * Shows how many of the [automatic] lane's chapters wait for their extension to be on, titled by their [series] when
 * they share one, or takes the notice away once none wait: they stay queued and download once it is back.
 */
internal fun showDownloadsWaiting(context: Context, automatic: Boolean, waiting: Int, series: Series?) {
    val tag = laneTag(automatic)
    if (waiting == 0) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, DOWNLOADS_WAITING_NOTIFICATION_ID)
    } else {
        postChaptersNotice(context, tag, DOWNLOADS_WAITING_NOTIFICATION_ID, waitingNotice(waiting, series))
    }
}

/** Takes away the waiting notice of each lane, by [automatic][QueuedChapter.automatic], with nothing left queued. */
internal fun clearIdleWaitingNotices(context: Context, queuedLanes: Set<Boolean>) {
    val manager = context.getSystemService(NotificationManager::class.java)
    listOf(false, true).filter { it !in queuedLanes }.forEach { manager.cancel(laneTag(it), DOWNLOADS_WAITING_NOTIFICATION_ID) }
}

private fun laneTag(automatic: Boolean) = if (automatic) "automatic" else "user"

/** A download notification about some chapters: titled by their series and opening its page when they share one. */
internal data class ChaptersNotice(val title: String, val text: String?, val series: SeriesId?)

/** "Ch. 12 could not be downloaded" under its series' title, or how many could not. */
internal fun failedNotice(failed: List<ChapterName>): ChaptersNotice {
    val counted = if (failed.size == 1) "1 chapter could not be downloaded" else "${failed.size} chapters could not be downloaded"
    val series = failed.sharedSeries() ?: return ChaptersNotice(counted, null, null)
    val one = failed.singleOrNull()?.chapter?.takeIf(String::isNotBlank)
    return ChaptersNotice(series.series, one?.let { "$it could not be downloaded" } ?: counted, series.seriesId)
}

/** "3 chapters waiting · Extension unavailable" under their [series]' title, or across series "5 chapters waiting". */
internal fun waitingNotice(waiting: Int, series: Series?): ChaptersNotice {
    val counted = if (waiting == 1) "1 chapter waiting" else "$waiting chapters waiting"
    if (series == null) return ChaptersNotice(counted, "Extensions unavailable", null)
    return ChaptersNotice(series.title, "$counted · Extension unavailable", series.id)
}

/** The first chapter, when every chapter is of its series and that series is stored. */
private fun List<ChapterName>.sharedSeries(): ChapterName? =
    firstOrNull()?.takeIf { first -> first.series.isNotBlank() && all { it.seriesId == first.seriesId } }

private fun postChaptersNotice(context: Context, tag: String?, id: Int, notice: ChaptersNotice) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    // One series' notice opens its page; across series it brings the app forward.
    val open = Intent(context, SeriesNotificationActivity::class.java).apply {
        notice.series?.let { action = Intent.ACTION_VIEW; data = seriesUri(it) }
    }
    val intent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = downloadNotification(context).setContentTitle(notice.title).setContentIntent(intent).setAutoCancel(true)
    notice.text?.let(builder::setContentText)
    manager.notify(tag, id, builder.build())
}

private fun downloadNotification(context: Context): Notification.Builder {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(DOWNLOADS_CHANNEL, "Chapter downloads", NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(context, DOWNLOADS_CHANNEL).setSmallIcon(R.drawable.ic_notification_series)
}

/**
 * Where a new-chapters or download notification lands: it names the series (and maybe an entry) for the running app
 * and brings the app forward. It is not exported, so only this app's notifications reach it, and [opened] is the only
 * way to a series from outside the app's own screens: a link another app sends cannot name one.
 */
class SeriesNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened.value = intent.data?.let(::seriesLinkOfUri)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    internal companion object {
        /** What a tapped notification named, until the app has opened it. */
        val opened = MutableStateFlow<SeriesLink?>(null)
    }
}

/** What a notification opens: a series' page, and with an [entry] address that entry in the Viewer over it. */
internal data class SeriesLink(val series: SeriesId, val entry: String? = null)

/** The new entry's name, or how many there are, as chapters or episodes by the series' kind. */
internal fun newEntriesText(update: SeriesUpdate): String =
    update.newEntries.singleOrNull()?.name ?: update.series.id.source.seriesKind.words.new(update.newEntries.size)

/** The address a notification's intent carries to [SeriesNotificationActivity]; [seriesLinkOfUri] reads it back. */
internal fun seriesUri(id: SeriesId, entry: String? = null): Uri = Uri.Builder().scheme(SERIES_URI_SCHEME).authority(SERIES_URI_HOST)
    .appendQueryParameter("source", id.source.id).appendQueryParameter("address", id.address)
    .apply { entry?.let { appendQueryParameter("entry", it) } }
    .build()

/** The series, and entry if any, a [seriesUri] names; null for any other address. */
internal fun seriesLinkOfUri(uri: Uri): SeriesLink? {
    if (uri.scheme != SERIES_URI_SCHEME || uri.authority != SERIES_URI_HOST) return null
    val source = uri.getQueryParameter("source")?.let(SourceKey::parse) ?: return null
    return SeriesLink(SeriesId(source, uri.getQueryParameter("address") ?: return null), uri.getQueryParameter("entry"))
}

private const val SERIES_URI_SCHEME = "theoriacodex"
private const val SERIES_URI_HOST = "series"
