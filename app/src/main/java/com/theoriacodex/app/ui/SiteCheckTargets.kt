package com.theoriacodex.app.ui

import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.source.SiteCheckTarget
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EXTENSION_SOURCE_ID_PREFIX
import com.theoriacodex.sources.http.HostOutageTracker

/**
 * The browser check [source] needs, for the shared check page; null when it has nothing to check. A built-in or stored
 * site's opens its home page with the browser's own agent, which completing saves for the site; a built-in site's
 * requests carry the browser's cookies while it is open, which a stored site's always do. An extension source's
 * opens the request the site refused, with the agent it refused, since the extension keeps sending that one, and is
 * passed once the extensions' own client gets through.
 */
internal fun siteCheckTarget(container: TheoriaAppContainer, source: SourceKey): SiteCheckTarget? {
    if (source.id.startsWith(EXTENSION_SOURCE_ID_PREFIX)) return extensionCheckTarget(container, source)
    val sites = container.customSites
    val stored = sites.sites.value.firstOrNull { it.sourceId == source.id }
    val (title, host) = stored?.let { it.label to it.host }
        ?: sites.builtInSites.value.firstOrNull { it.source == source }?.let { it.label to it.host }
        ?: return null
    return SiteCheckTarget(
        title = title,
        url = "https://$host/",
        cookieHost = host.takeIf { stored == null },
        passes = { userAgent -> sites.checkVerification(source, userAgent) },
        complete = { userAgent -> sites.completeVerification(source, userAgent) },
    )
}

private fun extensionCheckTarget(container: TheoriaAppContainer, source: SourceKey): SiteCheckTarget? {
    val extensions = container.extensionSites ?: return null
    val site = extensions.sites()[source] ?: return null
    val outages = container.sources.outages
    val (host, refused) = outages.outages.value.entries.firstNotNullOfOrNull { (host, outage) ->
        // Records outlive their challenge until the host is asked again, and one of those may no longer be reachable.
        outage.refused
            ?.takeIf { outages.isChallenged(it.url) && site.hosts.any { owned -> HostOutageTracker.hostBelongsToSite(host, owned) } }
            ?.let { host to it }
    } ?: return null
    return SiteCheckTarget(
        title = site.name,
        url = refused.url,
        userAgent = refused.userAgent,
        passes = { userAgent -> extensions.clearsChallenge(refused.url, userAgent) },
        complete = { outages.pass(host) },
    )
}
