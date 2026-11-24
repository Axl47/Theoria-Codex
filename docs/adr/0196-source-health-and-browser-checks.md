# 0196. Source health is derived from the outage tracker, and a browser check targets the request that was refused

Status: Accepted

## Context

A source that Cloudflare challenges can only be fixed by passing the check in a browser, with the same User-Agent the failed request used. Built-in sites, user-added sites and extension sources differ in what they can say about their own hosts, and outage records outlive the challenge that made them until the host is asked again.

## Decision

- `siteCheckTarget` returns the browser check a source needs, or null when it has nothing to check. A built-in or stored site opens its home page with the browser's own agent, which completing saves for the site; a built-in site's requests carry the browser's cookies only while its check is open (`cookieHost` is set only when the site is not stored), which allows an unverified host temporarily; a successful verification saves the built-in agent, and the cookie bridge keeps cookies for any host with a saved agent from then on. A stored site's requests always carry them. An extension source's check opens the request the site refused, with the agent it refused, since the extension keeps sending that one, and it passes once the extensions' own client gets through (`ExtensionSites.clearsChallenge`).
- An extension check looks only at records whose challenge is still active (`outages.isChallenged`) and whose host belongs to one of the site's hosts (`hostBelongsToSite`): records outlive their challenge until the host is asked again, and one of those may no longer be reachable.
- `rememberSourceHealth` starts the background health check once the app is showing (`navReady`), never blocking startup, with a connection warm-up and a Pixiv token refresh: an expired Pixiv token would otherwise refresh inside the first search, and having no account is not an error there. Issues are recomputed when outages change, when the extension sites change, and every minute so expired outages clear. Extension site hosts are read when outages change, since a source whose host was just challenged has loaded by then.
- A dismissed notice is forgotten when its outage ends (`dismissedNotices` is intersected with the current issues), so the next outage is announced again.

## Consequences

An extension source with no active challenge has no check target even if an outage record exists. Adding a source kind means deciding its `SiteCheckTarget` agent, cookie and completion rules here.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/SiteCheckTargets.kt`: `siteCheckTarget`, `extensionCheckTarget`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`: `rememberSourceHealth`
