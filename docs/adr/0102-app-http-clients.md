# 0102. Every app HTTP client derives from one shared client, and the app learns hosts and IPv6 health to cut first-request waits

Status: Accepted

## Context

The app talks to many hosts at once with different needs (images, video, provider APIs, whole-file downloads). OkHttp's defaults waste connections on bursts, five per host lets large GIFs stall every thumbnail, and a network with half-working IPv6 costs a connect timeout per address.

## Decision

- `AppHttpClients` derives every client from one so they share a connection pool; the clients differ in queueing and timeouts, plus the exceptions below. OkHttp 5 races IPv6 against IPv4 on each new connection, so broken IPv6 costs a quarter of a second, and `ConnectionMemory` removes even that. A host whose requests carry a browser identity connects as the WebView would (`BrowserAddressDns`), where its clearance cookie is accepted.
- The media base (video, images, downloads) replaces a request's User-Agent only when `verifiedUserAgent` supplies one for that URL, because a site's clearance cookie only counts with the agent that earned it; otherwise the request keeps its own agent, as provider requests and a browser check do.
- `images` (Coil) has its own per-host limit because the default of five lets a few large GIFs stall thumbnails; `sources` uses an unqueued dispatcher; `stalledPageRescue` has a pool of its own because a stalled page's connection is the one thing a rescue must not reuse. `downloads` also has its own connect and read timeouts and does not follow HTTPS-to-HTTP redirects. `MAX_IDLE_CONNECTIONS` is 32 because the default of five discards most of a burst and simultaneous HTTP/1.1 feed previews need one connection each.
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

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/network/AppHttpClientsDerivationTest.kt`: `every client shares the one connection pool, cookie jar and address lookup (ADR 0102)`, `a host holding a browser identity is looked up as the WebView would (ADR 0102)`, `each client keeps its own queueing, timeouts and redirect policy (ADR 0102)`, `warm-up reaches each recent host once per launch and skips hosts holding a browser identity (ADR 0102)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/network/AppHttpClientsTest.kt`: `media sends a verified site's browser agent while provider requests keep their own (ADR 0102)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/network/ConnectionMemoryTest.kt`: `IPv6 is set aside once IPv4 keeps winning, then gets another chance (ADR 0102)`, `a site reached over IPv6 proves it works (ADR 0102)`, `sites with nothing but IPv6 stay reachable (ADR 0102)`, `another network is judged on its own (ADR 0102)`, `IPv4 failing while IPv6 is set aside brings IPv6 back (ADR 0102)`, `the host list is not read from the store until it is asked for (ADR 0102)`, `recent hosts stay newest first and bounded (ADR 0102)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/network/StalledPageRescueTest.kt`: `a page that goes quiet carries on from its last byte (ADR 0102)`, `a rescue stops reading once the original call is cancelled (ADR 0102)`, `only nhentai page hosts are rescued unless told otherwise (ADR 0102)`, `the image client resumes a stalled page on another connection and stops reading once the call is cancelled (ADR 0102)`, `a host that ignores the range and sends the whole page again is read from the same byte (ADR 0102)`, `a page that stalls before its headers is fetched again and stops reading once the call is cancelled (ADR 0102)`
- `app/src/test/java/com/theoriacodex/app/diagnostics/NetworkUsageTest.kt`: `each client counts only its own traffic (ADR 0102)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/network/SharedPreferencesRecentHostStoreTest.kt`: `the saved host list opens its preferences only when first read (ADR 0102)`
- Left to prose: `MAX_IDLE_CONNECTIONS` cannot be read from outside `AppHttpClients` (OkHttp 5 hides a pool's idle limit), so it is not guarded, and the rescue's own pool is shown only as a new connection, not as a pool; the `= OkHttpClient()` defaults on `VideoPlaybackInfrastructure`, `videoHttpClient` and `PixivUgoiraClient` still let new code bypass these clients, because removing them touches about two dozen call sites in `:app` and the benchmark fixtures; that no request waits on `NetworkMonitor` is wiring no test pins; the stall lengths are measurements; not yet guarded, testable in app: that the stalled-page rescue's own requests count as image traffic.
