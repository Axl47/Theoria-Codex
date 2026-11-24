# 0113. Post downloads run from a durable queue that waits for sources, never duplicates a transfer and never deletes a finished file by itself

Status: Accepted

## Context

Downloads go through Android's DownloadManager and must survive process death, Activity recreation and removal while work is in flight. Provider media addresses expire, and the queue is restored before sources are ready.

## Decision

- `PostDownloads` is the app's one queue and the one writer of its job file, created once in the app container. Work runs in an application-lived scope on `Dispatchers.IO.limitedParallelism(1)`, so at most one queue task runs at a time and commands, workers and removals interleave only at suspension points. `PostDownloadQueue` guards its one-time restore with `restoration` and its saves with `persistence`. Downloads and a started removal continue after the Activity finishes; `removeNow` runs non-cancellable once started.
- `PostDownloadQueue` restores and shows its list at once, but each of its two workers waits on `startGate` before running an item, so restored work cannot run or fail before the sources it resolves through exist. A restored page takes its media from the freshly resolved post because saved URLs may have expired.
- A native transfer is found again by a random per-request marker (`downloadRequestMarker`, kept in the URI fragment, never in the HTTP target). A job whose `enqueuePending` flag is set without a native id is not re-enqueued, since a marker lost to a native redirect cannot authorize a duplicate of an unknown transfer; if Android cannot identify it, it stays failed. A restored transfer may still be waiting for a worker slot, so cancel also cancels native transfers.
- Cancel removes incomplete transfers only. Completed public files stay on the device; history-only clearing never deletes them, and finished items keep their status and are never cancelled, so a failed one can be retried. Removal stops workers before deleting records, covers a removed gallery's pages through `expandedFrom` (so one Undo restores them), and a confirmation can outlive the parent row while a gallery has become page downloads. Gallery expansion happens once after one authoritative resolution; selected Viewer pages never expand again.
- `commit` publishes the new list and saves it; if the save fails it publishes the rollback list and rethrows.
- `FileBackedPostDownloadJobStore` stores operational identities only: no media URLs, credentials, paths or full `Post` graphs. Versions 1 to 3 share one record (version 2 added `untrackedPreparation`, version 3 added `origin`); optional keys are omitted when null, as every earlier version wrote them, and `untrackedPreparation` is written only when true, while `enqueuePending` is always written, false included; an unreadable queue fails instead of dropping jobs. Origin labels saved as `FYP` are read back as `For You`.
- `PostDownloadFiles` resolves tracked Android destinations and never persists or guesses a provider's media path. Opening falls back to the canonical post inside the app when the file or an Android viewer is missing; cancellation stays cancellation. Restored jobs have no remote preview, so videos are decoded from their tracked local destination. An existing-file collision may point at a file with no successful native transfer.
- `AnimationExportDestination` publishes into the Downloads collection or a private file; the queue journals its marker before preparation, both destinations keep that identity, and a publish that committed before its caller lost the acknowledgement is found by key instead of written twice.

## Consequences

A crash at any point leaves a queue that resumes without double downloads, at the cost of leaving unidentifiable transfers failed for the user to retry. Changing the job record needs a golden for the older versions.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloads.kt`: `PostDownloads`, `removeNow`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadQueue.kt`: `PostDownloadQueue`, `PostDownloadItem`, `removeItems`, `commit`, `galleryDownloadPages`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadService.kt`: `downloadRequestMarker`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadJobStore.kt`: `FileBackedPostDownloadJobStore`, `DownloadItemRecord`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadFiles.kt`: `PostDownloadFiles`, `downloadedPostPreview`, `openDownloadedPostFile`, `openPostDownload`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/AnimationExportDestination.kt`: `AnimationExportDestination`
