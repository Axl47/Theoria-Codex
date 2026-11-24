# 0118. Video HTTP is HTTP/1.1 only and abandons half-read responses instead of draining them

Status: Accepted

## Context

HTTP/2's 16 MiB stream window would let a paused player buffer far more than a TCP window. OkHttp and `HttpURLConnection` drain the rest of a half-read HTTP/1.1 response for up to 100 ms to keep the connection, and every seek, scrub move and stopped player closes one part-way.

## Decision

- `videoHttpClient` is HTTP/1.1 only with no per-host request cap (OkHttp queues above its dispatcher limits), derived from the shared client to keep its pool, IPv4/IPv6 racing and cookies. The User-Agent comes from the stream's headers (its source's media headers), so a custom site's own agent, which its clearance cookies are usually bound to, applies to video.
- `AbandoningHttpDataSource` closes an unfinished response by cancelling its call. Over a response that long the drain ends by timing out and drops the connection anyway; cancelling a finished call is a no-op, so fully read responses keep their connection.

## Consequences

Seeks and scrubs do not wait on a drain; a cancelled call costs the connection of a half-read response.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoHttpDataSource.kt`: `videoHttpClient`, `AbandoningHttpDataSourceFactory`, `AbandoningHttpDataSource`
