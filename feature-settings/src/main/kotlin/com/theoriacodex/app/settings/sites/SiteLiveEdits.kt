package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteSummary
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

/**
 * Saves the Edit page's name, opt-in and headers shortly after each change, so only keys need a Save.
 * Only the latest values wait: a newer change to the same site replaces them, while a change to another site, a
 * typing pause or [flush] queues them as they are. Queued values are written one at a time, in order, in
 * [writeScope], which should outlive the page's owner, so a write already started or flushed by [close] still
 * completes; [timerScope] only times the typing pause.
 *
 * Each write compares the values with the site as saved now ([savedSite]) and sends what differs, so it never
 * rewrites a value saved elsewhere, such as the browser check's agent, and a change back to the saved value writes
 * nothing. A field this owner already wrote is always sent again, since [savedSite] can lag behind that write.
 */
internal class SiteLiveEdits(
    private val timerScope: CoroutineScope,
    writeScope: CoroutineScope,
    private val savedSite: (sourceId: String) -> CustomSiteSummary?,
    private val save: suspend (sourceId: String, edit: CustomSiteEdit) -> Boolean,
) {
    private val queue = Channel<Pair<String, LiveFields>>(Channel.UNLIMITED)
    private var waiting: Pair<String, LiveFields>? = null
    private var timer: Job? = null

    /** Per site, the fields this owner has written; touched only by the writer. */
    private val written = mutableMapOf<String, MutableSet<LiveField>>()

    init {
        writeScope.launch { for ((sourceId, fields) in queue) write(sourceId, fields) }
    }

    fun schedule(sourceId: String, fields: LiveFields, wait: Duration) {
        if (waiting?.first != sourceId) flush()
        timer?.cancel()
        waiting = sourceId to fields
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

    private suspend fun write(sourceId: String, fields: LiveFields) {
        val site = savedSite(sourceId) ?: return
        val sent = written.getOrPut(sourceId) { mutableSetOf() }
        val label = fields.label.trim().takeIf { it != site.label || LiveField.LABEL in sent }
        val include = fields.include.takeIf { it != site.includedInUnifiedAndForYou || LiveField.INCLUDE in sent }
        val headers = fields.headers?.takeIf { it != site.headers || LiveField.HEADERS in sent }
        if (label == null && include == null && headers == null) return
        if (!save(sourceId, CustomSiteEdit(label = label, headers = headers, includeInUnifiedAndForYou = include))) return
        if (label != null) sent += LiveField.LABEL
        if (include != null) sent += LiveField.INCLUDE
        if (headers != null) sent += LiveField.HEADERS
    }
}

private enum class LiveField { LABEL, INCLUDE, HEADERS }
