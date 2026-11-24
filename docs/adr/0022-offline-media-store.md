# 0022. Offline copies commit through a manifest and survive undecodable data without losing owners

Status: Accepted

## Context

Offline copies are shared by several owners (a Codex, saved posts, a series) and live outside disposable caches. Downloads can be stopped, deletes can be interrupted, and a manifest from another build may not decode.

## Decision

- The manifest is the commit point. Deleting removes it first, so whatever a stopped delete leaves is never a copy; a folder with media but no manifest is deleted at reconcile.
- Downloads are staged separately, so failure or cancellation never replaces a completed copy. `acquire` returns the media type found and the saved file and ref take it. First-page still images double as card previews; video and ZIP keep their image preview.
- Only manifest version 3 is usable. The folder name is derived from the post id digest, each page must normalize inside the copy folder (a lexical check, since the store is app-private), and manifest lengths must match file lengths; one length read per page confirms existence and completeness.
- Owners sit at the root of every manifest version and are salvaged from an undecodable manifest, counting only JSON strings (a lenient number would become an owner that blocks deletion). A folder without a usable copy keeps its salvaged owners so removing the last one deletes it, and removing an owner only deletes after ownership is confirmed on disk. An unreadable manifest is kept, not deleted.
- `admit` captures the owner's revision before queueing or resolving. `removeOwner(owner)` and `clear` invalidate waiting work; `removeOwner(owner, id)` leaves the owner's other admitted work valid, so the caller stops that post's save before removing it. The source post is stored beside the offline post so offline paths never replace canonical media.
- Offline image refs are tracked by identity with weak keys; the stored source refs never hold the offline keys, so a mapping lasts only as long as its Viewer refs. While a Viewer holds offline refs, their mapping to the source media can outlive the deleted files. The catalog's lead pages are detached copies for the same reason, and the catalog keeps no decoded posts.
- The pending job queue is device-only intent; an unreadable queue fails so its owner can offer a retry, never silently emptied.

## Consequences

Anything that moves or renames copy files must keep the manifest last. Raising the manifest version drops older copies.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/OfflineMediaStore.kt`: `OfflineMediaStore`, `admit`, `save`, `deleteCopy`, `readRecord`, `mediaMatchesManifest`, `salvageOwners`, `OfflineRefKey`, `OfflineManifest`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/OfflineMediaCatalog.kt`: `OfflineMediaCatalog`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/OfflinePendingJobStore.kt`: `OfflinePendingJobStore.load`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/OfflineMediaStoreTest.kt`: `a numeric owner never keeps a folder alive (ADR 0022)`
