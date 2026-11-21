package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.domain.source.SiteHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How long typing in the Edit page's name or header fields pauses before the change is saved. */
internal val TYPING_SAVE_DELAY = 600.milliseconds

/**
 * Saves the Edit page's name, opt-in and headers shortly after each change, so only keys need a Save.
 * Only the latest change waits: a newer one replaces it. [flush] saves a waiting change at once, for
 * leaving the page; [save] runs in [scope].
 */
internal class SiteLiveEdits(
    private val scope: CoroutineScope,
    private val save: suspend (sourceId: String, edit: CustomSiteEdit) -> Unit,
) {
    private var waiting: Pair<String, CustomSiteEdit>? = null
    private var timer: Job? = null

    fun schedule(sourceId: String, edit: CustomSiteEdit, wait: Duration) {
        timer?.cancel()
        waiting = sourceId to edit
        timer = scope.launch {
            delay(wait)
            saveWaiting()
        }
    }

    fun flush() {
        timer?.cancel()
        if (waiting != null) scope.launch { saveWaiting() }
    }

    private suspend fun saveWaiting() {
        val (sourceId, edit) = waiting ?: return
        waiting = null
        save(sourceId, edit)
    }
}

/**
 * The live part of an edit: only what differs from [site], so a change never rewrites a value saved
 * elsewhere meanwhile (the browser check's agent). [headers] is null when the fields are invalid; null
 * when nothing differs.
 */
internal fun liveEdit(site: CustomSiteSummary, fields: SiteFields, headers: SiteHeaders?): CustomSiteEdit? {
    val label = fields.label.trim().takeIf { it != site.label }
    val include = fields.includeInUnifiedAndForYou.takeIf { it != site.includedInUnifiedAndForYou }
    val changedHeaders = headers?.takeIf { it != site.headers }
    if (label == null && include == null && changedHeaders == null) return null
    return CustomSiteEdit(label = label, headers = changedHeaders, includeInUnifiedAndForYou = include)
}
