package com.theoriacodex.app.series

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.theoriacodex.app.MainActivity
import com.theoriacodex.app.R
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId

private const val NEW_CHAPTERS_CHANNEL = "new_chapters"
private const val NEW_CHAPTERS_GROUP = "new_chapters"

/** Every series' notification has this id and its series as the tag, so a later one for the same series replaces it. */
private const val SERIES_NOTIFICATION_ID = 1
private const val SUMMARY_NOTIFICATION_ID = 2

/**
 * Posts one local notification per series in [updates], grouped when there are several. Tapping one opens that
 * series' page. Without the notification permission nothing is posted; the new chapters are stored either way.
 */
internal fun notifySeriesUpdates(context: Context, updates: List<SeriesUpdate>) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (updates.isEmpty() || !manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(NotificationChannel(NEW_CHAPTERS_CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
    fun notification() = Notification.Builder(context, NEW_CHAPTERS_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_series)
        .setGroup(NEW_CHAPTERS_GROUP)
        .setAutoCancel(true)
    updates.forEach { update ->
        val open = Intent(Intent.ACTION_VIEW, seriesUri(update.series.id), context, MainActivity::class.java)
        manager.notify(
            update.series.id.toString(),
            SERIES_NOTIFICATION_ID,
            notification()
                .setContentTitle(update.series.title)
                .setContentText(newChaptersText(update.newEntries))
                .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build(),
        )
    }
    if (updates.size > 1) manager.notify(SUMMARY_NOTIFICATION_ID, notification().setGroupSummary(true).build())
}

/** The new chapter's name, or how many there are. */
internal fun newChaptersText(entries: List<SeriesEntry>): String = entries.singleOrNull()?.name ?: "${entries.size} new chapters"

/** The address a notification opens the app with; [seriesIdOfUri] reads it back. No other app can send it: no intent filter names it. */
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
