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

internal const val SERIES_NOTIFICATION_ID = 1
private const val SUMMARY_NOTIFICATION_ID = 2

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
    val replacing = manager.activeNotifications.any { it.id == SERIES_NOTIFICATION_ID && it.tag == tag }
    update.newEntries.singleOrNull()?.takeUnless { replacing }?.let { entry ->
        val action = update.series.id.source.seriesKind.words.open
        builder.addAction(Notification.Action.Builder(null, action, opening(entry.address)).build())
    }
    manager.notify(tag, SERIES_NOTIFICATION_ID, builder.build())
    val showing = manager.activeNotifications.filter { it.id == SERIES_NOTIFICATION_ID }.mapTo(mutableSetOf(tag)) { it.tag }
    if (showing.size > 1) {
        val app = PendingIntent.getActivity(context, 0, Intent(context, SeriesNotificationActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SUMMARY_NOTIFICATION_ID, notification().setGroupSummary(true).setContentIntent(app).build())
    }
}

private const val DOWNLOADS_CHANNEL = "chapter_downloads"

internal const val DOWNLOAD_PROGRESS_NOTIFICATION_ID = 3
private const val DOWNLOADS_FAILED_NOTIFICATION_ID = 4

private const val DOWNLOADS_WAITING_NOTIFICATION_ID = 5

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

internal fun downloadProgressText(progress: DrainProgress): String =
    listOf(progress.chapter, "${progress.number} of ${progress.of}").filter(String::isNotBlank).joinToString(" · ")

internal fun postDownloadProgress(context: Context, notification: Notification) {
    context.getSystemService(NotificationManager::class.java).notify(DOWNLOAD_PROGRESS_NOTIFICATION_ID, notification)
}

internal fun cancelDownloadProgress(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(DOWNLOAD_PROGRESS_NOTIFICATION_ID)
}

internal fun notifyDownloadsFailed(context: Context, failed: List<ChapterName>) {
    postChaptersNotice(context, null, DOWNLOADS_FAILED_NOTIFICATION_ID, failedNotice(failed))
}

internal fun showDownloadsWaiting(context: Context, automatic: Boolean, waiting: Int, series: Series?) {
    val tag = laneTag(automatic)
    if (waiting == 0) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, DOWNLOADS_WAITING_NOTIFICATION_ID)
    } else {
        postChaptersNotice(context, tag, DOWNLOADS_WAITING_NOTIFICATION_ID, waitingNotice(waiting, series))
    }
}

internal fun clearIdleWaitingNotices(context: Context, queuedLanes: Set<Boolean>) {
    val manager = context.getSystemService(NotificationManager::class.java)
    listOf(false, true).filter { it !in queuedLanes }.forEach { manager.cancel(laneTag(it), DOWNLOADS_WAITING_NOTIFICATION_ID) }
}

private fun laneTag(automatic: Boolean) = if (automatic) "automatic" else "user"

internal data class ChaptersNotice(val title: String, val text: String?, val series: SeriesId?)

internal fun failedNotice(failed: List<ChapterName>): ChaptersNotice {
    val counted = if (failed.size == 1) "1 chapter could not be downloaded" else "${failed.size} chapters could not be downloaded"
    val series = failed.sharedSeries() ?: return ChaptersNotice(counted, null, null)
    val one = failed.singleOrNull()?.chapter?.takeIf(String::isNotBlank)
    return ChaptersNotice(series.series, one?.let { "$it could not be downloaded" } ?: counted, series.seriesId)
}

internal fun waitingNotice(waiting: Int, series: Series?): ChaptersNotice {
    val counted = if (waiting == 1) "1 chapter waiting" else "$waiting chapters waiting"
    if (series == null) return ChaptersNotice(counted, "Extensions unavailable", null)
    return ChaptersNotice(series.title, "$counted · Extension unavailable", series.id)
}

private fun List<ChapterName>.sharedSeries(): ChapterName? =
    firstOrNull()?.takeIf { first -> first.series.isNotBlank() && all { it.seriesId == first.seriesId } }

private fun postChaptersNotice(context: Context, tag: String?, id: Int, notice: ChaptersNotice) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
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

class SeriesNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.data?.let(::seriesLinkOfUri)
        if (link?.entry != null) getSystemService(NotificationManager::class.java).cancel(link.series.toString(), SERIES_NOTIFICATION_ID)
        opened.value = link
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    internal companion object {
        val opened = MutableStateFlow<SeriesLink?>(null)
    }
}

internal data class SeriesLink(val series: SeriesId, val entry: String? = null)

internal fun newEntriesText(update: SeriesUpdate): String =
    update.newEntries.singleOrNull()?.name ?: update.series.id.source.seriesKind.words.new(update.newEntries.size)

internal fun seriesUri(id: SeriesId, entry: String? = null): Uri = Uri.Builder().scheme(SERIES_URI_SCHEME).authority(SERIES_URI_HOST)
    .appendQueryParameter("source", id.source.id).appendQueryParameter("address", id.address)
    .apply { entry?.let { appendQueryParameter("entry", it) } }
    .build()

internal fun seriesLinkOfUri(uri: Uri): SeriesLink? {
    if (uri.scheme != SERIES_URI_SCHEME || uri.authority != SERIES_URI_HOST) return null
    val source = uri.getQueryParameter("source")?.let(SourceKey::parse) ?: return null
    return SeriesLink(SeriesId(source, uri.getQueryParameter("address") ?: return null), uri.getQueryParameter("entry"))
}

private const val SERIES_URI_SCHEME = "theoriacodex"
private const val SERIES_URI_HOST = "series"
