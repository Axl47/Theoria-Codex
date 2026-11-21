package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.HeaderEdit
import com.theoriacodex.domain.source.SiteHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How long typing in the Edit page's name or header fields pauses before the change is saved. */
internal val TYPING_SAVE_DELAY = 600.milliseconds

/** What the user last left in the Edit page's live fields; [headers] is null while those fields are invalid. */
internal data class LiveFields(val label: String, val include: Boolean, val headers: SiteHeaders?)

/** One live field of the Edit page; a header is written on its own, so the other stays as stored. */
internal enum class LiveField { LABEL, INCLUDE, USER_AGENT, REFERER }

/** A site's waiting change: the fields the user [changed] and the values they left in them. */
private data class SiteChange(val sourceId: String, val changed: Set<LiveField>, val fields: LiveFields)

/**
 * Saves the Edit page's name, opt-in and headers shortly after each change, so only keys need a Save.
 * Only the fields the user changes are ever written, with the last values left in them: a newer change to the same
 * site joins the waiting one, while a change to another site, a typing pause or [flush] queues it as it is. Queued
 * changes are written one at a time, in order, in [writeScope], which should outlive the page's owner, so a write
 * already started or flushed by [close] still completes; [timerScope] only times the typing pause.
 *
 * A changed field that equals the site as saved now ([savedSite]) is left out, so a change back to the saved value
 * writes nothing; one this owner already wrote is always sent again, since [savedSite] can lag behind that write.
 */
internal class SiteLiveEdits(
    private val timerScope: CoroutineScope,
    writeScope: CoroutineScope,
    private val savedSite: (sourceId: String) -> CustomSiteSummary?,
    private val save: suspend (sourceId: String, edit: CustomSiteEdit) -> Boolean,
) {
    private val queue = Channel<SiteChange>(Channel.UNLIMITED)
    private var waiting: SiteChange? = null
    private var timer: Job? = null

    /** Per site, the fields this owner has written; touched only by the writer. */
    private val written = mutableMapOf<String, MutableSet<LiveField>>()

    init {
        writeScope.launch { for (change in queue) write(change) }
    }

    /** Records that the user changed [field], leaving the form at [fields]; it is written after [wait]. */
    fun schedule(sourceId: String, field: LiveField, fields: LiveFields, wait: Duration) {
        if (waiting?.sourceId != sourceId) flush()
        timer?.cancel()
        waiting = SiteChange(sourceId, waiting?.changed.orEmpty() + field, fields)
        timer = timerScope.launch {
            delay(wait)
            enqueueWaiting()
        }
    }

    fun flush() {
        timer?.cancel()
        enqueueWaiting()
    }

    /** Flushes, then lets the writer finish what is queued and stop; for when the owner is cleared. */
    fun close() {
        flush()
        queue.close()
    }

    private fun enqueueWaiting() {
        waiting?.let(queue::trySend)
        waiting = null
    }

    private suspend fun write(change: SiteChange) {
        val site = savedSite(change.sourceId) ?: return
        val sent = written.getOrPut(change.sourceId) { mutableSetOf() }
        val fields = change.fields
        val sending = change.changed.filterTo(mutableSetOf()) { field ->
            fields.carries(field) && (field in sent || fields.differs(field, site))
        }
        if (sending.isNotEmpty() && save(change.sourceId, fields.edit(sending))) sent += sending
    }
}

/** False for a header while the header fields are invalid, which writes neither header. */
private fun LiveFields.carries(field: LiveField) = headers != null || field == LiveField.LABEL || field == LiveField.INCLUDE

private fun LiveFields.differs(field: LiveField, site: CustomSiteSummary): Boolean = when (field) {
    LiveField.LABEL -> label.trim() != site.label
    LiveField.INCLUDE -> include != site.includedInUnifiedAndForYou
    LiveField.USER_AGENT -> headers?.userAgent != site.headers.userAgent
    LiveField.REFERER -> headers?.referer != site.headers.referer
}

/** An edit that writes exactly [fields] (all carried) and keeps everything else as stored. */
private fun LiveFields.edit(fields: Set<LiveField>) = CustomSiteEdit(
    label = label.trim().takeIf { LiveField.LABEL in fields },
    includeInUnifiedAndForYou = include.takeIf { LiveField.INCLUDE in fields },
    userAgent = if (LiveField.USER_AGENT in fields) HeaderEdit.Replace(headers?.userAgent) else HeaderEdit.Keep,
    referer = if (LiveField.REFERER in fields) HeaderEdit.Replace(headers?.referer) else HeaderEdit.Keep,
)
