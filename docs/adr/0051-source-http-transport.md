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
