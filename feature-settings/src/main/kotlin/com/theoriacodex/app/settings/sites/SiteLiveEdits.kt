package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.HeaderEdit
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val TYPING_SAVE_DELAY = 600.milliseconds

internal data class LiveFields(val label: String, val include: Boolean, val userAgent: String = "", val referer: String = "")

internal enum class LiveField { LABEL, INCLUDE, USER_AGENT, REFERER }

private data class SiteChange(val sourceId: String, val changed: Set<LiveField>, val fields: LiveFields)

internal class SiteLiveEdits(
    private val timerScope: CoroutineScope,
    writeScope: CoroutineScope,
    private val savedSite: (sourceId: String) -> CustomSiteSummary?,
    private val save: suspend (sourceId: String, edit: CustomSiteEdit) -> Boolean,
) {
    private val queue = Channel<SiteChange>(Channel.UNLIMITED)
    private var waiting: SiteChange? = null
    private var timer: Job? = null

    private val written = mutableMapOf<String, MutableSet<LiveField>>()
    private val failed = mutableMapOf<String, Set<LiveField>>()

    init {
        writeScope.launch { for (change in queue) write(change) }
    }

    fun schedule(sourceId: String, field: LiveField, fields: LiveFields, wait: Duration) {
        if (waiting?.sourceId != sourceId) {
            flush()
            waiting = null
        }
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

    fun close() {
        flush()
        queue.close()
    }

    private fun enqueueWaiting() {
        val change = waiting ?: return
        val (ready, invalid) = change.changed.partition(change.fields::valid)
        if (ready.isNotEmpty()) queue.trySend(change.copy(changed = ready.toSet()))
        waiting = change.copy(changed = invalid.toSet()).takeIf { invalid.isNotEmpty() }
    }

    private suspend fun write(change: SiteChange) {
        val site = savedSite(change.sourceId) ?: return
        val sent = written.getOrPut(change.sourceId) { mutableSetOf() }
        val fields = change.fields
        val changed = change.changed + failed.remove(change.sourceId).orEmpty().filter(fields::valid)
        val sending = changed.filterTo(mutableSetOf()) { field -> field in sent || fields.differs(field, site) }
        if (sending.isEmpty()) return
        if (save(change.sourceId, fields.edit(sending))) sent += sending else failed[change.sourceId] = sending
    }
}

private fun LiveFields.valid(field: LiveField): Boolean = when (field) {
    LiveField.LABEL -> label.trim().length in 1..SiteProfile.MAX_LABEL_LENGTH
    LiveField.INCLUDE -> true
    LiveField.USER_AGENT -> runCatching { SiteHeaders(userAgent = header(userAgent)) }.isSuccess
    LiveField.REFERER -> runCatching { SiteHeaders(referer = header(referer)) }.isSuccess
}

private fun LiveFields.differs(field: LiveField, site: CustomSiteSummary): Boolean = when (field) {
    LiveField.LABEL -> label.trim() != site.label
    LiveField.INCLUDE -> include != site.includedInUnifiedAndForYou
    LiveField.USER_AGENT -> header(userAgent) != site.headers.userAgent
    LiveField.REFERER -> header(referer) != site.headers.referer
}

private fun LiveFields.edit(fields: Set<LiveField>) = CustomSiteEdit(
    label = label.trim().takeIf { LiveField.LABEL in fields },
    includeInUnifiedAndForYou = include.takeIf { LiveField.INCLUDE in fields },
    userAgent = if (LiveField.USER_AGENT in fields) HeaderEdit.Replace(header(userAgent)) else HeaderEdit.Keep,
    referer = if (LiveField.REFERER in fields) HeaderEdit.Replace(header(referer)) else HeaderEdit.Keep,
)

private fun header(typed: String): String? = typed.trim().ifEmpty { null }
