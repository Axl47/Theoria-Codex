# 0205. The Viewer route consumes its launch payload once, runs effects in order against current state, and extends live sessions off the main thread

Status: Accepted

## Context

A Viewer session is launched from a feed, a Codex or History, can be restored after process death, and keeps growing while its source pages. Effects such as media recovery wait on providers, and a session can be replaced while effects are still buffered.

## Decision

- The activity owner only holds a pending, process-local navigation payload. The destination consumes it once, restores a compact saved identity when necessary (a cold reconstruction is the saved ordered window, never a fresh or unrelated feed, so `liveSearchBinding` is false), and then keeps all mutations, work and effects behind `ViewerViewModel`.
- `ViewerRouteLiveSourceState` holds the live feeds a stream can extend from. `matching` returns a feed's snapshot only while the source still shows the launch's query (same `queryHash`); exact query identity keeps a source refresh or route replacement from appending unrelated posts. Visibility policy is applied at the Viewer boundary so newly paged posts obey the launch filters.
- `appendLiveSourcePosts` filters the source's feed and finds the posts the session lacks on a background context, since both walk whole lists; the caller's thread only appends the result and builds pages for the new posts alone.
- `handleViewerEffect` runs against the state at delivery, not at emission, and effects run in order because the collector awaits each one. `isCurrentFor` drops effects from another session, so buffered or suspended effects never cross a session replacement. Media recovery waits on a provider, so it runs in `routeScope` where it cannot hold a later Dismiss behind it, and `mediaRetries` (a `Mutex`) keeps manual retries in order so an older retry cannot finish after a newer one and replace its post. Tests also pin that a recovery the Viewer no longer wants is dropped instead of recorded, and that a media recovery needs fresh URLs so it never joins a request already in flight.
- A fork's posts are not in the grid underneath, so only a root level retains its posts for the card return (`viewerReturn?.retainPosts`).
- Duration work for the session is keyed by canonical post identity and waits only on the lifecycle, since the Viewer has no scrolling feed; the list handed to the duration owner is remembered so an unchanged session is recognized as no new work. Screen callbacks are bound to the owner, one per owner and shell, so controls stay put while Search, For You or settings change; they read the owner's current session when they run.
- New likes extend the related drawer instead of replacing it for as long as the session lasts.
- `preparePostsForLaunch` normalizes galleries on `providerDispatcher`, since normalizing every gallery can traverse thousands of media refs before navigation.
- `recordVisibleMediaProgress` strips offline locations (`withoutOfflineLocations`) from the post before writing it, because this callback can create the History membership before the visible-post write completes.

## Consequences

Effects are ordered and session-bound; slow provider work cannot block dismissal. A new effect that waits on a provider needs to run outside the ordered collector.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt`: `ViewerRoute`, `ViewerRouteLiveSourceState`, `appendLiveSourcePosts`, `handleViewerEffect`, `isCurrentFor`, `ViewerDurationSync`, `rememberViewerScreenHost`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRouteWorkflow.kt`: `preparePostsForLaunch`, `restoreSession`, `recordVisibleMediaProgress`
