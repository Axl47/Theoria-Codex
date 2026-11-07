package com.theoriacodex.app.di

import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.sourceauth.SiteCredentialsStore
import com.theoriacodex.data.repository.SiteProfileRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteRequestContext
import com.theoriacodex.sources.http.SourceHttpClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One stored site as the runtime sees it. [adapter] is null while the site is unusable. */
data class CustomSiteState(val profile: SiteProfile, val hasCredentials: Boolean, val adapter: SourceAdapter?) {
    val source: SourceKey get() = SourceKey.of(profile)

    val availability: CustomSiteAvailability
        get() = when {
            profile.family == null -> CustomSiteAvailability.UNSUPPORTED_FAMILY
            adapter == null -> CustomSiteAvailability.NEEDS_CREDENTIALS
            else -> CustomSiteAvailability.AVAILABLE
        }
}

/** An immutable view of every user-added site; [revision] changes with each rebuild. */
data class CustomSourceSnapshot(val revision: Long, val sites: List<CustomSiteState>) {
    /** Usable sites only: a known family, and keys stored when the site requires them. */
    val adapters: Map<SourceKey, SourceAdapter> = sites.mapNotNull { site -> site.adapter?.let { site.source to it } }.toMap()

    companion object {
        val EMPTY = CustomSourceSnapshot(revision = 0, sites = emptyList())
    }
}

/**
 * Turns stored profiles and keys into registered adapters (plan C4). Rebuilt whenever profiles or
 * custom-site keys change, without an app restart. Adapters hold a key snapshot, so a key edit
 * takes effect through the rebuild.
 */
internal class CustomSourceRuntime(
    profiles: SiteProfileRepository,
    private val credentials: SiteCredentialsStore,
    private val families: SiteFamilyRegistry,
    /** A [com.theoriacodex.sources.custom.CustomSiteHttpClient]: HTTPS only, vetted redirects. */
    private val http: SourceHttpClient,
    scope: CoroutineScope,
) {
    private val revisions = AtomicLong()

    val snapshot: StateFlow<CustomSourceSnapshot> =
        combine(profiles.observeProfiles(), credentials.changes) { stored, _ -> stored }
            .map(::build)
            .stateIn(scope, SharingStarted.Eagerly, CustomSourceSnapshot.EMPTY)

    private suspend fun build(profiles: List<SiteProfile>): CustomSourceSnapshot {
        val sites = profiles.map { profile ->
            val keys = credentials.get(profile.sourceId)
            val family = profile.family?.let(families::familyFor)
            val usable = family != null && (!profile.credentialsRequired || keys != null)
            val adapter = family?.takeIf { usable }?.createAdapter(
                profile = profile,
                source = SourceKey.of(profile),
                context = SiteRequestContext(http = http, headers = profile.headers, credentials = keys),
            )
            CustomSiteState(profile, hasCredentials = keys != null, adapter = adapter)
        }
        // B3 seam: SourcePresentationCatalog.installCustom(...) must run here, before this snapshot
        // (and so its adapters) is published, and withdraw removed entries only after. Waits for
        // step 2's B3; until then custom sources fall back to an unknown-source presentation.
        return CustomSourceSnapshot(revision = revisions.incrementAndGet(), sites = sites)
    }
}
