# 0149. Offline copies and downloads are removed as deferred destruction, and a Codex's offline ring counts only current posts

Status: Accepted

## Context

The Library's offline and downloads sheet lets users remove stored media. Removals delete files, so they need Undo, must survive rotation, and must not leave work behind (queued downloads) or stale progress in the ring shown on Codex tiles.

## Decision

- Removing an offline copy (`OfflineStorageViewModel.removeDeferred`), all copies (`removeAll`) and downloads (`DownloadRemovals`) uses `DeferredRemovals`: the row hides at once with Undo in its place, and the files go only when the Undo lifetime ends. Commits run in a `removalScope` that must be application-lived, and clearing the owner commits what is still pending. Leaving the surface that shows the Undo commits every pending removal; a rotation does not.
- `removeAll` takes over the single removals still waiting, so its one Undo puts every copy back and no single Undo outlives the copies it names. Committing it also cancels queued series chapter downloads, or the next drain would download them again.
- Committing a removal asks the coordinator to stop preparations first; `makeAvailableOffline` tops up a copy (posts already retained are skipped), and the coordinator owns the loading, so removing the copy or deleting the Codex stops it before clearing.
- A removal of a download also covers the pages a removed gallery expands into while it waits, so they stay hidden and the gallery's first page keeps its one Undo row (`TransfersSheetContent`).
- The offline ring of a Codex (`codexOfflineStates`) counts the share of its current posts that are settled (available offline or failed in the last run); posts no longer in the Codex do not count, and a failed post that left the Codex no longer matters while a failure of the whole run always does. Codices whose members are not yet known get no ring, and neither does a request the user stopped before anything was kept (only the sheet lists it, with Retry). The ring's description gives no percent when failed, because failed posts count as settled and a failed ring is often full.
- On a Library tile the ring is status only, so a tap anywhere on the tile opens the Codex.

## Consequences

Undo of a removal is a plain un-hide and process death deletes nothing. Anything that adds offline work for an owner must also be stopped by that owner's removal.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/media/OfflineStorageViewModel.kt`: `removeDeferred`, `removeAll`, `commitRemoval`, `makeAvailableOffline`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/DownloadRemovals.kt`: `DownloadRemovals`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/CodexOfflineRemovalUndo.kt`: `CodexOfflineRemovalUndo`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/LibraryTransfersSheet.kt`: `TransfersSheetContent`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/OfflineOwners.kt`: `codexOfflineStates`
- `feature-library/src/main/kotlin/com/theoriacodex/app/media/CodexOfflineRing.kt`: `codexOfflineDescription`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexOfflineRingFor.kt`: `CodexOfflineStatusFor`
