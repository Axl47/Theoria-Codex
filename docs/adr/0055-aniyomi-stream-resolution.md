# 0055. Anime streams resolve from all servers concurrently, with per-server and list time limits

Status: Accepted

## Context

An Aniyomi episode names servers (`getHosterList`), then each resolves to videos. Some sources resolve every server inside the list call. Extension code can ignore cancellation. Measured: every server that answered in a sample did so within 6.2 s; a server list that resolves every server itself took up to 31 s on a phone-speed network and longer on an emulator. The Viewer waits 60 s for an episode.

## Decision

- Every server is asked at once. `SERVER_LIMIT_MS` is 6 s per server and `LIST_LIMIT_MS` is 50 s for the list, so list plus one server stays inside the 60 s. Tests shorten them via `Limits`.
- A server's limit can still allow an in-flight hidden-browser pass (up to 10 s); this is accepted. A cut-off server's work and the request it waits on are cancelled and the pass ends unrecorded, so the next try restarts it; a request made outside any coroutine runs on and a clearance it earns serves the next try.
- Some servers answering is enough. When none has a video, the most useful failure is thrown (a browser check first since the user can pass it, else the first), else a timeout; servers that answered with no videos give an empty list. Each failure is converted via `calls::failure` even when another server answers, so a browser check that stopped one is reported.
- Videos are matched to servers by identity (two servers may offer equal videos), sorted by the source's `sortVideos`, then stably by `preferred`.
- `declaresServerList` decides as Aniyomi does: some class of the extension's own, from the source type up to the host classes, declares one of its steps. The declared interface version does not tell, and calling the host default would fetch the episode page, which sources keeping an id or full address cannot answer. An uninspectable class is taken to have it. Without it the older single call answers as one `NO_HOSTER_LIST` server.
- Interface 14's constructor stores a missing address as the text "null"; those are treated as no address. A stream without headers gets the source's, including its browser agent, since some hosts refuse requests without them. The manifest type comes from the address first (a server named "HLS" can offer a plain file), else the quality name.
- An anime whose source groups into seasons is refused with words the series page shows as they are; only its details tell, so an anime not opened in this process is opened first (one extra request).
- The interface version is the major part of the version name; one host serves 14 and 16 because nearly every 14 extension is built against 16's server-list API. Torrent extensions need an engine the app lacks.

## Consequences

Slow sources lose servers after 6 s rather than blocking playback. Changing the 60 s Viewer wait requires revisiting both limits.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiStreams.kt`: `AniyomiStreams`, `Limits`, `SERVER_LIMIT_MS`, `LIST_LIMIT_MS`, `of`, `servers`, `videos`, `finished`, `hasAddress`, `toStream`, `impliedMime`, `declaresServerList`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSource.kt`: `entries`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiKind.kt`: `unsupported`
