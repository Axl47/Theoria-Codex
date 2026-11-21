package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.domain.source.SiteHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How long typing in the Edit page's name or header fields pauses before the change is saved. */
internal val TYPING_SAVE_DELAY = 600.milliseconds

/** What the user last left in the Edit page's live fields; [headers] is null while those fields are invalid. */
internal data class LiveFields(val label: String, val include: Boolean, val headers: SiteHeaders?)

/**
 * Saves the Edit page's name, opt-in and headers shortly after each change, so only keys need a Save.
 * Only the latest values wait: a newer change replaces them, and [flush] sends them at once, for leaving the page.
 * Writes run one at a time in [writeScope], which should outlive the page's owner, so a write already started or
 * flushed as the owner is cleared still completes; [timerScope] only times the typing pause.
 *
 * Each write sends only what differs from the site as saved (so it never rewrites a value saved elsewhere, such as
 * the browser check's agent), counting this owner's own writes that [savedSite] may not show yet. A change back
 * to a value already written is therefore written again, and a change back to the saved value writes nothing.
 */
internal class SiteLiveEdits(
    private val timerScope: CoroutineScope,
    private val writeScope: CoroutineScope,
    private val savedSite: (sourceId: String) -> CustomSiteSummary?,
    private val save: suspend (sourceId: String, edit: CustomSiteEdit) -> Boolean,
) {
    private val latest = AtomicReference<Pair<String, LiveFields>?>(null)
    private val writes = Mutex()

    /** Per site, what this owner's successful writes saved; touched only while holding [writes]. */
    private val written = mutableMapOf<String, CustomSiteEdit>()
    private var timer: Job? = null

    fun schedule(sourceId: String, fields: LiveFields, wait: Duration) {
        timer?.cancel()
        latest.set(sourceId to fields)
        timer = timerScope.launch {
            delay(wait)
            launchWrite()
        }
    }

    fun flush() {
        timer?.cancel()
        launchWrite()
    }

    private fun launchWrite() {
        if (latest.get() == null) return
        writeScope.launch {
            writes.withLock {
                while (true) {
                    val (sourceId, fields) = latest.getAndSet(null) ?: break
                    val site = savedSite(sourceId) ?: continue
                    val edit = liveEdit(site.after(written[sourceId]), fields) ?: continue
                    if (save(sourceId, edit)) written[sourceId] = written[sourceId]?.let { edit.over(it) } ?: edit
                }
            }
        }
    }
}

/** Only what differs from [site]; null when nothing does. Invalid headers ([LiveFields.headers] null) never differ. */
private fun liveEdit(site: CustomSiteSummary, fields: LiveFields): CustomSiteEdit? {
    val label = fields.label.trim().takeIf { it != site.label }
    val include = fields.include.takeIf { it != site.includedInUnifiedAndForYou }
    val headers = fields.headers?.takeIf { it != site.headers }
    if (label == null && include == null && headers == null) return null
    return CustomSiteEdit(label = label, headers = headers, includeInUnifiedAndForYou = include)
}

/** This site once [edit] (a live edit already saved) is applied. */
private fun CustomSiteSummary.after(edit: CustomSiteEdit?): CustomSiteSummary = if (edit == null) this else copy(
    label = edit.label ?: label,
    headers = edit.headers ?: headers,
    includedInUnifiedAndForYou = edit.includeInUnifiedAndForYou ?: includedInUnifiedAndForYou,
)

/** This live edit written after [earlier]: its values win, and [earlier]'s fill the rest. */
private fun CustomSiteEdit.over(earlier: CustomSiteEdit) = CustomSiteEdit(
    label = label ?: earlier.label,
    headers = headers ?: earlier.headers,
    includeInUnifiedAndForYou = includeInUnifiedAndForYou ?: earlier.includeInUnifiedAndForYou,
)
