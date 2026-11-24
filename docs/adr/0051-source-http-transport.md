# 0051. Source HTTP stays HTTPS-only, byte-exact, unqueued and cancellable

Status: Accepted

## Context

Provider requests can carry API keys in query strings, arrive in bursts to one host, and sometimes use byte ranges. Several JDK, OkHttp and provider behaviours would otherwise leak keys, serialize requests or corrupt ranges.

## Decision

- Custom-site transport never follows redirects blindly: it follows at most `maxRedirects` hops, only HTTPS to HTTPS on the same host and port, and a host and its `www.` twin count as one site, so keys never leave that site. A refused redirect is never requested. `DefaultSourceHttpClient` follows redirects, including to other hosts, but is built with `followSslRedirects(false)`, so a redirect never changes scheme (HTTPS to HTTP and back).
- `unqueuedSourceDispatcher` raises both the total and per-host limits to 128 requests, queuing only above that: pages of metadata hit one host in bursts and HTTP/2 multiplexes them, so OkHttp's default per-host limit of five would serialize them.
- Ranged requests ask for identity encoding, because Android's `HttpURLConnection` inflates gzip transparently and a range can end mid-stream, producing EOF or a Content-Range that describes different bytes.
- `executeCancellableHttpConnection` cancels the caller immediately and disconnects off the caller's thread, since some JDKs hold the read lock inside `disconnect()`. The blocking operation must have finite timeouts.
- `unexpectedFailuresAsIoExceptions` turns every `RuntimeException` (such as the `SecurityException` from blocked DNS) into an `IOException`, because OkHttp rethrows anything else on its dispatcher threads and kills the app. `Call.await` reads the body on OkHttp's thread and cancels the call when the caller is cancelled.
- Requests keep the platform `http.agent` where a source sets none, because some providers reject library agents. Danbooru's Cloudflare challenges generic browser agents (`Mozilla/5.0`) on its API and CDN, so its traffic sends `THEORIA_SOURCE_USER_AGENT`.
- Query-component spaces are encoded as `%20`, not `+`, for wider API compatibility.
- Retries: 5xx are retried, 429s are retried except for Iwara, whose rate limit counts each retry and lasts longer for it. A `Retry-After` of more than a few seconds ends retrying (Danbooru's maintenance page sends 30 s).

## Consequences

Custom-site keys stay on TLS and on one site, so a custom site that legitimately redirects across hosts is refused. Callers must pass identity-encoded range headers. Revisiting the dispatcher limit means re-measuring burst behaviour per host.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/custom/CustomSiteHttpClient.kt`: `vetRedirect`, `maxRedirects`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/DefaultSourceHttpClient.kt`: `unqueuedSourceDispatcher`, `httpTransport`, `PLATFORM_AGENT_PROPERTY`, query encoding
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/SourceHttpClient.kt`: `THEORIA_SOURCE_USER_AGENT`, range headers
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/CancellableHttpConnection.kt`: `executeCancellableHttpConnection`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/OkHttpCalls.kt`: `unexpectedFailuresAsIoExceptions`, `Call.await`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/http/SourceRetry.kt`: `defaultShouldRetryStatus`, `retryDelayMs`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/custom/CustomSiteHttpClientTest.kt`: `follows same-site HTTPS redirects, including relative ones and the www twin (ADR 0051)`, `refuses a redirect to plain HTTP without requesting it (ADR 0051)`, `refuses redirects to another host or port without requesting them (ADR 0051)`, `stops after three redirect hops (ADR 0040, 0051)`, `the shipped client never lets the transport follow a redirect (ADR 0051)`, `the shipped client follows three hops and retries a transient 503 once outages are tracked (ADR 0040, 0050, 0051)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapterTest.kt`: `score search spends a term on its order metatag and keeps numbered pages (ADR 0041, 0051, 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/http/CancellableHttpConnectionTest.kt`: `cancelling the caller returns at once and disconnects a stalled read (ADR 0051)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/http/DefaultSourceHttpClientTest.kt`: `blocked network access is an ordinary failure instead of a crash (ADR 0051)`, `the default source client turns a blocked lookup into an ordinary failure (ADR 0051)`, `cancellation disconnects an active post response read (ADR 0051)`, `returns exact binary range response and headers (ADR 0051)`, `encodes query spaces as percent-20 for get requests (ADR 0051)`, `a redirect may change host but never scheme (ADR 0051)`, `same-host requests beyond OkHttp's default per-host limit run at once (ADR 0051)`, `a request without its own agent carries the platform http agent (ADR 0051)`, `iwara 429 responses are not retried by default policy (ADR 0051)`, `non iwara 429 responses still retry by default policy (ADR 0051)`, `server errors still retry by default policy (ADR 0051)`, `a long retry after is not waited out (ADR 0051)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/http/HostOutageTrackerTest.kt`: `a maintenance page fails at once and the next request never reaches the host (ADR 0050, 0051)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/network/AppHttpClientsTest.kt`: `every app client turns a blocked lookup into an ordinary failure (ADR 0051)`
- Left to prose: `executeCancellableHttpConnection` disconnecting off the caller's thread matters only on JDKs whose `disconnect()` holds the read lock, and its finite timeouts are its caller's; the reasons behind the 128-request limit are burst measurements; the warm-up client `AppHttpClients.warmRecentHosts` builds is unguarded, because it is a local value inside that function that no test can reach (it is built from `shared`, like the others).
