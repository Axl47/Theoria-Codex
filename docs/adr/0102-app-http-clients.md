# 0102. Every app HTTP client derives from one shared client, and the app learns hosts and IPv6 health to cut first-request waits

Status: Accepted

## Context

The app talks to many hosts at once with different needs (images, video, provider APIs, whole-file downloads). OkHttp's defaults waste connections on bursts, five per host lets large GIFs stall every thumbnail, and a network with half-working IPv6 costs a connect timeout per address.

## Decision

- `AppHttpClients` derives every client from one so they share a connection pool; the clients differ in queueing and timeouts, plus the exceptions below. OkHttp 5 races IPv6 against IPv4 on each new connection, so broken IPv6 costs a quarter of a second, and `ConnectionMemory` removes even that. A host whose requests carry a browser identity connects as the WebView would (`BrowserAddressDns`), where its clearance cookie is accepted.
- The media base (video, images, downloads) replaces a request's User-Agent only when `verifiedUserAgent` supplies one for that URL, because a site's clearance cookie only counts with the agent that earned it; otherwise the request keeps its own agent, as provider requests and a browser check do.
- `images` (Coil) has its own per-host limit because the default of five lets a few large GIFs stall thumbnails; `sources` uses an unqueued dispatcher; `stalledPageRescue` has a pool of its own because a stalled page's connection is the one thing a rescue must not reuse. `downloads` also has its own connect and read timeouts and does not follow HTTPS-to-HTTP redirects. `MAX_IDLE_CONNECTIONS` is 32 because the default of five discards most of a burst and simultaneous HTTP/1.1 feed previews need one connection each.
- The media base records a 429 in the shared `HostOutageTracker` (`recordingRateLimits`) so background picture work stands down, without holding off pictures the user opens (ADR 0050).
- `warmRecentHosts` runs once per launch and connects to the hosts used most recently so the first search finds connections open; hosts holding a clearance cookie are skipped because the warm-up agent would not match the one that earned it. The host list is read lazily, because the store is built with the Application before its context can open preferences.
- `ConnectionMemory` turns IPv6 off for `IPV6_AVOID_MS` after IPv4 beat IPv6 on `IPV6_LOSSES_TO_AVOID` sites offering both, then gives it another chance; another network starts clean. A failure with IPv6 ruled out distrusts the judgement.
- Debug builds count each client's traffic; derived clients copy interceptors, so only final clients are counted.
- `StalledPageRescue` continues an nhentai page that goes silent for `stallMs`: its hosts sometimes stop sending for 15 to 30 seconds (about one page in forty when measured) while a new request on another connection answers in about a second. The rescue resumes from the last byte; because it is a separate call, `StopsWithCall` makes each read check the original call.
- `NetworkMonitor` decides when to retry and how to word a failure; requests are never blocked on it, so a wrong reading costs no feed.

## Consequences

First requests after launch and after a network change are faster; one host's stall or large files cannot starve the others.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/network/AppHttpClients.kt`: `AppHttpClients`, `warmRecentHosts`, `MAX_IDLE_CONNECTIONS`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/ConnectionMemory.kt`: `ConnectionMemory`, `RecentHostStore`
- `media-kit/src/main/kotlin/com/theoriacodex/app/network/StalledPageRescue.kt`: `StalledPageRescue`, `StopsWithCall`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/NetworkMonitor.kt`: `NetworkMonitor`
