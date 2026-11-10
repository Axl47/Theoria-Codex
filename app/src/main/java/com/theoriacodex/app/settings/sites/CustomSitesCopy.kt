package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.BuiltInTestResult
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile

// User-facing copy for Settings → Sites. Pure functions so every outcome is covered by host tests.

/** Explains a probe result that can't be saved as is. Null for [SiteProbeResult.Supported]. */
internal fun probeMessage(result: SiteProbeResult): String? = when (result) {
    is SiteProbeResult.Supported -> null
    is SiteProbeResult.CredentialsRequired -> if (result.rejected) {
        KEYS_REJECTED_MESSAGE
    } else {
        "${result.address.host} needs a ${userFieldLabel(result.family).lowercase()} and API key."
    }
    is SiteProbeResult.UnsupportedFamily ->
        "This looks like a ${result.familyName} site, which Theoria can't use yet."
    is SiteProbeResult.NotABooru -> "${result.address.host} doesn't answer like a supported booru."
    is SiteProbeResult.AlreadyBuiltIn -> alreadyBuiltInMessage(result.builtInSourceId)
    is SiteProbeResult.Unreachable -> unreachableMessage(result.address.host, result.reason)
    is SiteProbeResult.InvalidAddress -> invalidAddressMessage(result.error)
}

/** What Done says when the app's own request is still refused after the person's check. */
internal fun verificationRefusedMessage(host: String): String =
    "$host is still refusing Theoria. Finish its check, or try again in a moment."

/** Shown when the first check passes, so a page opened voluntarily stays open. */
internal fun verificationAlreadyOpenMessage(host: String): String =
    "$host already lets Theoria through. Tap Done to save this browser for it."

internal const val VERIFICATION_INSTRUCTION =
    "Complete the site's check above. This closes by itself when Theoria gets through."
internal const val BROWSER_UNAVAILABLE_MESSAGE = "The system browser isn't available right now."

internal fun verificationLoadFailedMessage(host: String): String = "Couldn't load $host. Check your connection and try again."

/** Names the shipped source when the id parses, e.g. "Danbooru is already built into Theoria." */
internal fun alreadyBuiltInMessage(builtInSourceId: String): String =
    SourceKey.parse(builtInSourceId)?.takeUnless { it.isCustom }?.let { "${it.displayName()} is already built into Theoria." }
        ?: "This site is already built into Theoria."

internal fun unreachableMessage(host: String, reason: ProbeFailure): String = when (reason) {
    ProbeFailure.DNS -> "Couldn't find $host. Check the address."
    ProbeFailure.TLS -> "Couldn't connect securely to $host."
    ProbeFailure.TIMEOUT -> "$host took too long to answer. Try again."
    ProbeFailure.HTTP_ERROR -> "$host answered with an error. Try again later."
    ProbeFailure.BLOCKED -> "$host blocked the check, possibly with a browser challenge. If you have an account there, your API key may get through."
    ProbeFailure.INSECURE_REDIRECT -> "$host redirected to an insecure address."
    ProbeFailure.CROSS_HOST_REDIRECT -> "$host redirected to a different site."
}

internal fun invalidAddressMessage(error: SiteAddressError): String = when (error) {
    SiteAddressError.MALFORMED -> "Enter a site address, like safebooru.org."
    SiteAddressError.NOT_HTTPS -> "Only HTTPS sites can be added."
    SiteAddressError.UNSUPPORTED_HOST -> "Use the site's domain name, not an IP address."
    SiteAddressError.TOO_LONG -> "That address is too long."
}

/** Danbooru signs in with a login name, Gelbooru 0.2 with a numeric user ID. */
internal fun userFieldLabel(family: ProtocolFamily?): String = when (family) {
    ProtocolFamily.DANBOORU -> "Username"
    ProtocolFamily.GELBOORU_02 -> "User ID"
    null -> "User ID or username"
}

/**
 * Helper under the user field once the family is known. Gelbooru 0.2 user IDs are numeric, so a
 * non-numeric entry is flagged ([UserFieldHint.error]); Danbooru logins can be anything.
 */
internal fun userFieldHint(family: ProtocolFamily?, user: String): UserFieldHint? = when (family) {
    ProtocolFamily.DANBOORU -> UserFieldHint("Danbooru sites use your username, not a numeric ID.", error = false)
    ProtocolFamily.GELBOORU_02 -> {
        val wrong = user.isNotBlank() && !user.trim().all(Char::isDigit)
        UserFieldHint(
            if (wrong) "Gelbooru sites need your numeric user ID, not your username."
            else "Your numeric user ID, shown with the API key on the site's account options page.",
            error = wrong,
        )
    }
    null -> null
}

internal data class UserFieldHint(val text: String, val error: Boolean)

/** What a re-test found; a failure's message shows under the row. Never suggests the profile changed. */
internal fun retestStatus(site: CustomSiteSummary, result: SiteProbeResult): RetestStatus.Done {
    val answeredFamily = when (result) {
        is SiteProbeResult.Supported -> result.family
        is SiteProbeResult.CredentialsRequired -> result.family
        else -> null
    }
    return when {
        answeredFamily != null && answeredFamily != site.family -> RetestStatus.Done(
            "This site now answers as ${answeredFamily.displayName}. Remove it and add it again to switch.",
            ok = false,
        )
        result is SiteProbeResult.Supported -> RetestStatus.Done("Working", ok = true)
        result is SiteProbeResult.CredentialsRequired -> RetestStatus.Done(
            if (result.rejected) KEYS_REJECTED_MESSAGE else "Needs an API key.",
            ok = false,
            needsKey = true,
        )
        else -> RetestStatus.Done(
            probeMessage(result).orEmpty(),
            ok = false,
            blocked = result is SiteProbeResult.Unreachable && result.reason == ProbeFailure.BLOCKED,
        )
    }
}

/** What a real search through a built-in source found, in the shape its row shows. */
internal fun builtInTestStatus(site: BuiltInSiteSummary, result: BuiltInTestResult): RetestStatus.Done = when (result) {
    BuiltInTestResult.WORKING -> RetestStatus.Done("Working", ok = true)
    BuiltInTestResult.NEEDS_BROWSER_CHECK -> RetestStatus.Done("Needs browser verification.", ok = false, blocked = true)
    BuiltInTestResult.NEEDS_ACCOUNT -> RetestStatus.Done("Needs an account or API key.", ok = false)
    BuiltInTestResult.DOWN -> RetestStatus.Done("Down for maintenance. Try again later.", ok = false)
    BuiltInTestResult.UNREACHABLE -> RetestStatus.Done(unreachableMessage(site.host, ProbeFailure.TIMEOUT), ok = false)
    BuiltInTestResult.FAILED -> RetestStatus.Done(unreachableMessage(site.host, ProbeFailure.HTTP_ERROR), ok = false)
}

/** The status line while Test all runs. */
internal fun testProgressLine(progress: SitesTestProgress): String =
    "Testing ${progress.label}… ${progress.position} of ${progress.total}"

/** The status line once Test all has finished. */
internal fun testSummaryLine(working: Int, total: Int): String = "$working of $total working"

/** Explains a save, edit or removal that didn't happen. Null for [CustomSiteMutation.Success]. */
internal fun mutationMessage(result: CustomSiteMutation): String? = when (result) {
    CustomSiteMutation.Success -> null
    is CustomSiteMutation.DuplicateHost -> "This site is already in your list."
    is CustomSiteMutation.ReconnectConfirmationNeeded -> "Confirm reconnecting ${result.hostLabel} first."
    CustomSiteMutation.LimitReached ->
        "You can add up to ${SiteProfile.MAX_CUSTOM_SITES} sites. Remove one first."
    CustomSiteMutation.NotFound -> "This site is no longer in your list."
    CustomSiteMutation.CredentialStoreUnavailable -> "Secure key storage isn't available right now. Try again."
    is CustomSiteMutation.InvalidLabel -> "Use a name of 1 to ${result.maxLength} characters."
}

/** Status words for a row. Always text, so state never depends on color alone. */
internal fun availabilityLabel(availability: CustomSiteAvailability): String = when (availability) {
    CustomSiteAvailability.AVAILABLE -> "Ready"
    CustomSiteAvailability.NEEDS_CREDENTIALS -> "Needs API key"
    CustomSiteAvailability.UNSUPPORTED_FAMILY -> "Unsupported in this version"
}

/** Second line of a built-in source's row: host, its account status, and whether a browser check is needed. */
internal fun builtInRowSummary(site: BuiltInSiteSummary, account: String?): String =
    listOfNotNull(site.host, account, "Needs browser verification".takeIf { site.needsVerification }).joinToString(" · ")

/** Second line of a row: host, then [siteStatusSummary]. */
internal fun siteRowSummary(site: CustomSiteSummary): String = "${site.host} · ${siteStatusSummary(site)}"

/** Family and status, plus the Unified/For You opt-in when on. */
internal fun siteStatusSummary(site: CustomSiteSummary): String = buildList {
    add(site.family?.displayName ?: "Unknown type")
    add(availabilityLabel(site.availability))
    if (site.includedInUnifiedAndForYou) add("In Unified and For You")
}.joinToString(" · ")

/** The Settings home row's value. */
internal fun customSitesSummary(sites: List<CustomSiteSummary>): String {
    if (sites.isEmpty()) return "Add a booru by its address"
    val count = "${sites.size} added"
    val needingKeys = sites.count { it.availability == CustomSiteAvailability.NEEDS_CREDENTIALS }
    return if (needingKeys == 0) count else "$count · $needingKeys need an API key"
}

internal fun removeConfirmationText(label: String): String =
    "Remove $label? Likes, Codex items and history from this site stay in your library, " +
        "but can't be opened or refreshed until you add the site again."

internal fun reconnectConfirmationText(hostLabel: String): String =
    "Reconnect your existing likes and history from $hostLabel? Only do this if it is the same site."

internal val HEADER_ERROR_MESSAGE = "Header values must be a single line of up to ${SiteHeaders.MAX_HEADER_LENGTH} characters."
internal const val ADD_API_KEY_LABEL = "Add API key"
internal const val KEYS_REJECTED_MESSAGE = "Those keys didn't work. Check them, and that the account has API access."
internal const val CONFIRM_KEY_HINT = "This site checks visitors first. If you have an account there, add your API key so browsing isn't limited."
internal const val PARTIAL_CREDENTIALS_MESSAGE = "Enter both the user and the API key, or leave both blank."
internal const val UNEXPECTED_FAILURE_MESSAGE = "Something went wrong. Try again."
