# 0201. Destinations draw from activity-wide read models and retained flows so a returning screen needs no new query

Status: Accepted

## Context

Screens leave and re-enter composition constantly while others sit on top of them in the back stack. A destination that re-queries on every return draws blank or reflows, and a Viewer or Codex opened from a feed would otherwise wait on its own fresh queries for state the app already has. Opening a large Codex decodes longer than its opening animation.

## Decision

- Shared read models are owned by the activity, not by a navigation entry: `browsingMetadataOwner` (`BrowsingMetadataViewModel`) and `CodexCollectionViewModel` use `activityViewModelStoreOwner()`. `BrowsingMetadata` carries settings plus the posts whose file is liked, saved or watched: stored IDs and every post known this session that holds the same file on another site (`FileMirrorIndex`). Badges and filters read IDs only; stored posts are read beside that, once each, to teach the index their file and picture hashes, so mirrors appear shortly after the IDs. While metadata is observed it reads the watch history once and the Library once, then only the posts stored since (up to `SINGLE_READS` = 20 one by one, more in a single Library read, as on first load or after a restore), so no decoded Library stays in memory.
- A profile switch never pairs the new profile with the previous profile's Likes: `state` emits nothing until the Likes belong to the active profile.
- `CodexCollectionViewModel` holds every Codex's post count and cover candidates for all profiles, so a save costs one summary query and one thumbnail listing however many screens show covers; each screen filters by profile. `thumbnailCacheCleared` resolves covers again after the thumbnail cache deleted files they pointed at.
- `RetainedFlowsViewModel` keeps a navigation entry's last values per key (`rememberRetained`); a flow is shared with `WhileSubscribed(5_000)` semantics, and the first flow given for a key is kept for the entry's life, so the key must identify everything the flow depends on. The Codex content instance (`rememberCodexContentFlows`, activity-scoped, at most `MAX_CODEX_CONTENT_FLOWS` = 12 flows, about four Codices) evicts least recently used flows that have no subscriber once it holds more than that; eviction cancels sharing, which would otherwise hold the last value, and a flow on screen stays live. `CodexContentWarmUp` prepares the `WARM_CODEX_LIMIT` (2) largest Codices with at least `WARM_CODEX_MIN_ITEMS` (200) items while the Library shows, since only they outlast the opening animation.
- The Library boundary returns no content until the Codices and saved searches have answered, because a placeholder grid with only virtual tiles would clamp or move the restored collection anchor. Statistics for a usage sort are subscribed only while that sort needs them, because statistics are written during use. A sort change keeps showing the previous order until the new one answers (`ShownPosts`). The shared collection presentation spans every profile, but warm-up and tiles consider only the active profile's Codices.
- The Save-to-Codex sheet keeps what the last open loaded instead of restarting from defaults, but reads usage (`observeCodexLibraryUsage`) and last-saved times once per open: watching behind the sheet writes usage and a save from behind it would otherwise move rows under a tap.
- `DestinationStateBoundary` reads its state inside its own composable, so the read invalidates that restart scope rather than the caller's. A caller that also reads the same state itself, as `CodexDestinationStateBoundary` does to wait for settings, is still invalidated by its own read.
- The Settings destination subscribes to statistics only while visible. The Viewer destination reads only what a Viewer needs from each feed (`viewerFeedChanges`), so the rest of a feed's state never recomposes it. `ActiveProfileCoordinationEffect` waits for real settings so it never acts on the default profile while they load.
- `RouteOwnerSlot` is the shell's reference to a navigation-owned ViewModel that the shell reaches but never owns. A destination publishes its owner while composed; the slot empties itself when that ViewModel is cleared, so the shell keeps reaching a still-live back-stack owner (a Viewer paging the feed under it) but never one whose entry is gone. Publishing a replacement stops watching the previous owner, and `withdraw` only empties the slot if the leaving owner still holds it. An owner cleared before publication empties the slot at once, inside `register`. Each ViewModel keeps one cleared signal as a keyed closeable, because an entry can leave and re-enter composition without its ViewModel being cleared.

## Consequences

Returning screens draw from memory, and a save or a profile switch is one query shared by every screen. The cost is memory held for retained flows (bounded for Codex contents) and care in choosing retained keys: a key that omits an input shows stale data for the entry's life.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/routes/BrowsingMetadataViewModel.kt`: `BrowsingMetadata`, `BrowsingMetadataViewModel`, `mirrorLinks`, `SINGLE_READS`
- `app/src/main/java/com/theoriacodex/app/ui/routes/CodexCollectionViewModel.kt`: `CodexCollectionViewModel`, `thumbnailCacheCleared`
- `app/src/main/java/com/theoriacodex/app/ui/routes/RetainedFlows.kt`: `RetainedFlowsViewModel`, `rememberRetained`, `rememberCodexContentFlows`
- `app/src/main/java/com/theoriacodex/app/ui/routes/RouteOwnerSlot.kt`: `RouteOwnerSlot`, `OwnerClearedSignal`, `clearedSignal`
- `app/src/main/java/com/theoriacodex/app/ui/routes/DestinationStateBoundaries.kt`: `browsingMetadataOwner`, `activityViewModelStoreOwner`, `DestinationStateBoundary`, `CodexDestinationStateBoundary`, `CodexContentWarmUp`, `ActiveProfileCoordinationEffect`
