# 0050. Host outages are tracked per host and Cloudflare checks are passed by a shared hidden browser

Status: Accepted

## Context

Providers go down, serve maintenance pages, or sit behind Cloudflare browser checks. Without shared state every feature would wait on a dead host in turn, and each challenged request would open its own browser. Cloudflare binds the clearance cookie to the network address and the User-Agent that earned it.

## Decision

- `HostOutageTracker` is an in-memory per-host circuit breaker shared by all source clients. Callers that go through `ensureAvailable` (the retrying source and custom-site clients) fail at once with `HostUnavailableException` for a known-down host until the outage expires or is cleared. The extension `CloudflareInterceptor` does not fast-fail: it still sends requests to a host with a standing challenge, skips its hidden browser pass while that challenge stands, and clears the challenge when a request succeeds. Unresolvable domains count only while online and only after a second failed lookup, because a network that is still coming up fails every lookup briefly. Outages are recorded after retries, so a request that recovers leaves nothing behind. `hostBelongsToSite` runs on every request and builds no strings.
- A challenge is recognised by Cloudflare's `cf-mitigated: challenge` header (a 503 challenge is a challenge, never maintenance), is final for retries, and is not renewed while active; a recorded challenge gains the refused request if it was recorded without one. Failures show a message that says what to do, such as wait, verify or re-add.
- `ChallengePass` loads the page in one of the shared `ChallengeBrowsers` (one pass per host, at most `MAX_BROWSERS` = 2) and resends the request once a clearance cookie other than the refused one appears. The refused cookie is expired first (as Mihon does); a cookie earned meanwhile counts as new; cookies are flushed after a pass; a pass cut short by cancellation is not recorded as a failure; a pass that failed while a request waited is not retried and leaves the check to the user.
- Requests that may carry secrets load only the site root with the request's own agent (`siteRoot`). The clearance User-Agent is the one that earned it (`userAgentFor`, `RefusedRequest`). No hidden browser runs for the site whose check the user has on screen, since it would expire the cookie the visible one just earned. No pass runs from the main thread.
- `BrowserAddressDns` resolves browser-cookie hosts to the addresses the WebView connects to, because Cloudflare refuses the cookie over the other IP family. It drops IPv6 when the system lists IPv4 first.
- The cookie jar serves only allowed hosts; a throwing store means no cookies, never an exception on a request thread. `BrowserCookieStore` is a port so rules are tested without `android.webkit`.
- `SiteHealthCheck` is a light untracked home-page request: an oversized page still counts as answered. `countChallenges` is for custom sites, whose gates cover every page; a built-in may challenge its home page while its API works. `clearsChallenge` judges by status and headers alone and leaves the tracker alone until the check is completed.
- `requestWithChallengeFallback` returns `CHALLENGED_RESPONSE` (a synthetic 403) for a challenge the client already turned into an outage, so the caller can route to a mirror while the outage stays recorded.

## Consequences

One failing host cannot stall other features, and one hidden browser serves all clients. Changing the cookie, agent or address rules risks Cloudflare re-challenging the user.

## Applies to

- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt`: `CloudflareInterceptor`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/HostOutageTracker.kt`: `HostOutageTracker`, `ensureAvailable`, `RefusedRequest`, `hostBelongsToSite`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/ChallengePass.kt`: `ChallengePass`, `ChallengeBrowsers`, `siteRoot`, `isCloudflareChallenge`, `challengedRequest`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/BrowserAddressDns.kt`: `BrowserAddressDns`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/BrowserCookies.kt`: `BrowserCookieStore`, `BrowserCookieJar`, `hasNewClearance`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/DefaultSourceHttpClient.kt`: `userAgentFor`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/SourceRetry.kt`: `executeWithRetry`, `delayBeforeRetry`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/SiteHealthCheck.kt`: `SiteHealthCheck`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceAdapterCommon.kt`: failure message for `HostUnavailableException`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceRequests.kt`: `requestWithChallengeFallback`, `CHALLENGED_RESPONSE`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/CustomSiteHttpClient.kt`: `sendTracked`
