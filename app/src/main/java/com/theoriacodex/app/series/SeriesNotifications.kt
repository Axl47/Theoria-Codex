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
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.MutableStateFlow

private const val NEW_CHAPTERS_CHANNEL = "new_chapters"
private const val NEW_CHAPTERS_GROUP = "new_chapters"

/** Every series' notification has this id and its series as the tag, so a later one for the same series replaces it. */
private const val SERIES_NOTIFICATION_ID = 1
private const val SUMMARY_NOTIFICATION_ID = 2

/**
 * Posts [update]'s local notification, and the group's summary once two series have one showing. Tapping it opens
 * that series' page. Without the notification permission nothing is posted; the new chapters are stored either way.
 */
internal fun notifySeriesUpdate(context: Context, update: SeriesUpdate) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(NotificationChannel(NEW_CHAPTERS_CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
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
            .setContentText(newChaptersText(update.newEntries))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build(),
    )
    // Counted with this one by name: the system may not list a notification the moment it is posted.
    val showing = manager.activeNotifications.filter { it.id == SERIES_NOTIFICATION_ID }.mapTo(mutableSetOf(tag)) { it.tag }
    if (showing.size > 1) manager.notify(SUMMARY_NOTIFICATION_ID, notification().setGroupSummary(true).build())
}

/**
 * Where a new-chapters notification lands: it names the series for the running app and brings the app forward. It is
 * not exported, so only this app's notifications reach it, and [opened] is the only way to a series from outside the
 * app's own screens: a link another app sends cannot name one.
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

/** The new chapter's name, or how many there are. */
internal fun newChaptersText(entries: List<SeriesEntry>): String = entries.singleOrNull()?.name ?: "${entries.size} new chapters"

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
