# 0027. Chapter downloads run from a two-lane queue with one worker

Status: Accepted

## Context

Downloading chapters involves expiring page addresses, per-source rate limits, sources users can turn off, and queue changes racing with a running fetch.

## Decision

- A downloaded chapter is the offline copy of its chapter post, kept by `seriesOfflineOwner`; the offline store is the truth for what is on the device. The queue file under `noBackupFilesDir` is the only other state, and offline copies are also left out of backups so a restore never brings back a queue without downloads or the reverse.
- Two lanes (user-queued, automatic) each have their own job. Drains fetch one chapter at a time across both lanes (the `drains` mutex), which also keeps each source to one chapter. `lock` guards the queue, its file and the drain state.
- A chapter's pages are requested just before fetching, since addresses expire. A chapter that is unavailable (no pages, 404/410, not an image) leaves the queue; other failures keep it queued and its source sits out the drain. A source that is off waits without asking for a retry, since only the user can turn it on, which reschedules the lane.
- User-queued chapters run on the networks the download settings allow; automatic ones only on an unmetered network.
- Rescheduling on source or network change replaces the lane's job with today's requirements and no failure backoff. A sources change leaves a draining lane alone; a network change replaces the user's job even while draining.
- A lane stops draining in the same step that finds nothing to try, so a chapter queued afterwards is scheduled. A new drain takes over a cancelled drain's lane token.
- Queue mutations (`download`, `delete`, `deleteSeries`, `cancelAll`) finish even if the caller is cancelled so the queue and its file stay in step. The fetch job is registered before it can run, so a removal that does not find it active knows it never starts.
- Episodes never download; `downloadNew` reports failures such as a full disk instead of throwing, so storing new chapters still counts as done. Extension page types come from the fetched response.
- A copy already on the device (for example from a Codex) only gains the series as owner. With Delete after reading on, delete-after-reading removes each downloaded chapter that is read, except the one the series was last opened at. It runs once no reader is open and at startup (`resume`), and stops if a reader opens, so a chapter that reader opened keeps its files.
- A source id this build cannot read drops the chapter, not the queue.

## Consequences

Removing all offline copies mid-fetch fails the commit, so callers use `cancelAll` first.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesDownloads.kt`: `SeriesDownloads`, `drain`, `fetchNext`, `rescheduleOnChanges`, `downloadNew`, `deleteRead`, `resume`, `episodePost`, `seriesOfflineOwner`, `QueuedChapterRecord`, `DownloadScheduler`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SeriesDownloadsTest.kt`: `the files decide what is downloaded whatever the database or a lost queue says (ADR 0027, 0028)`, `deleting a chapter or a series leaves the rest (ADR 0027)`, `each lane has its own job, and a running one never holds back the other (ADR 0027)`, `the two lanes never fetch at the same time (ADR 0027)`, `an unavailable chapter leaves the queue and the lane goes on (ADR 0027)`, `a page is tried a bounded number of times and a failure that may pass keeps the chapter and holds its source (ADR 0027)`, `a source that is off holds only its own chapters, which the drain reports as waiting (ADR 0027)`, `lanes are scheduled again when the sources change, and the user's lane, draining too, when the network settings do (ADR 0027, 0028)`, `a replaced drain stops without dropping its chapter, and leaves the lane to the drain that replaced it (ADR 0027, 0028)`, `new episodes never download, whatever the anime's setting says (ADR 0027)`, `new chapters download by themselves only for a followed series set to (ADR 0027)`, `a chapter already kept offline, such as in a Codex, is not fetched again (ADR 0027)`, `delete after reading removes read chapters except where the series was last opened, also at start (ADR 0027)`, `a chapter fetched while every offline copy is removed is not kept, and stays queued unless cancelled first (ADR 0027)`, `queuing a chapter finishes even when its caller is cancelled (ADR 0027)`, `a queued chapter whose source this build cannot read is dropped and the rest load (ADR 0027)`
- `app/src/test/java/com/theoriacodex/app/AppJobTest.kt`: `automatic downloads wait for an unmetered network while user downloads follow the download settings (ADR 0027)`
- Left to prose: downloads stay out of backups because the backup series record has no download field and the queue file's `noBackupFilesDir` location is app wiring; that delete-after-reading stops when a reader opens, and that `downloadNew` reports a full disk instead of throwing are timing and failure paths the test fakes cannot reach without new hooks.
