package com.theoriacodex.app.di

import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.source.CUSTOM_PRESENTATION_ORDER_START
import com.theoriacodex.app.source.SourcePresentation
import com.theoriacodex.app.source.SourcePresentationCatalog
import com.theoriacodex.app.source.customSourcePresentation
import com.theoriacodex.app.sourceauth.SiteCredentialsStore
import com.theoriacodex.data.repository.SiteProfileRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteRequestContext
import com.theoriacodex.sources.http.SourceHttpClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Credentials

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
 * Turns stored profiles and keys into registered adapters and presentations (plan C4, C5). Rebuilt
 * whenever profiles or custom-site keys change, without an app restart. Adapters hold a key
 * snapshot, so a key edit takes effect through the rebuild.
 */
internal class CustomSourceRuntime(
    profiles: SiteProfileRepository,
    private val credentials: SiteCredentialsStore,
    private val families: SiteFamilyRegistry,
    /** A [com.theoriacodex.sources.custom.CustomSiteHttpClient]: HTTPS only, vetted redirects. */
    private val http: SourceHttpClient,
    scope: CoroutineScope,
    /** Receives the `Authorization` value of each site whose API accepts its key that way, by host. */
    private val publishMediaAuthorizations: (Map<String, String>) -> Unit = {},
    private val installPresentations: (List<SourcePresentation>) -> Unit = SourcePresentationCatalog::installCustom,
) {
    private val revisions = AtomicLong()
    private val mutableSnapshot = MutableStateFlow(CustomSourceSnapshot.EMPTY)
    private var installed: List<SourcePresentation> = emptyList()

    val snapshot: StateFlow<CustomSourceSnapshot> = mutableSnapshot.asStateFlow()

    init {
        scope.launch {
            combine(profiles.observeProfiles(), credentials.changes) { stored, _ -> stored }
                .collect { stored -> publish(build(stored)) }
        }
    }

    /**
     * Any available source always has a presentation: new entries are installed before the snapshot
     * (and so its adapters) is published, and removed entries are withdrawn only after.
     */
    private fun publish(next: CustomSourceSnapshot) {
        val presentations = next.sites.mapIndexed { index, site -> site.presentation(CUSTOM_PRESENTATION_ORDER_START + index) }
        installPresentations((presentations + installed).distinctBy(SourcePresentation::source))
        mutableSnapshot.value = next
        installPresentations(presentations)
        installed = presentations
    }

    private suspend fun build(profiles: List<SiteProfile>): CustomSourceSnapshot {
        val mediaAuthorizations = HashMap<String, String>()
        val sites = profiles.map { profile ->
            val keys = credentials.get(profile.sourceId)
            if (keys != null && profile.family == ProtocolFamily.DANBOORU) {
                mediaAuthorizations[profile.address.host] = Credentials.basic(keys.user, keys.apiKey, Charsets.UTF_8)
            }
            val family = profile.family?.let(families::familyFor)
            val usable = family != null && (!profile.credentialsRequired || keys != null)
            val adapter = family?.takeIf { usable }?.createAdapter(
                profile = profile,
                source = SourceKey.of(profile),
                context = SiteRequestContext(http = http, headers = profile.headers, credentials = keys),
            )
            CustomSiteState(profile, hasCredentials = keys != null, adapter = adapter)
        }
        publishMediaAuthorizations(mediaAuthorizations)
        return CustomSourceSnapshot(revision = revisions.incrementAndGet(), sites = sites)
    }
}

/** Label from the profile; Referer and User-Agent overrides apply to media requests (plan C5). */
private fun CustomSiteState.presentation(order: Int): SourcePresentation = customSourcePresentation(
    source = source,
    label = profile.label,
    baseUrl = profile.address.baseUrl,
    referer = profile.headers.referer,
    userAgent = profile.headers.userAgent,
    order = order,
    family = profile.family,
    iconUrl = profile.iconUrl,
)
