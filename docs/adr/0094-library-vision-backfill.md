# 0094. Library picture indexing is paced, thermally gated and bounded in retries

Status: Accepted

## Context

Indexing liked and saved posts runs on a phone: it must not heat it, hold the CPU, or hammer providers when the network is down. Whether a post plays is known on the device, and a model migration once marked every earlier analysis as awaiting frames.

## Decision

- `drain` (charging-only job) analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. It stops when done, when the model goes or will not load, or when the device runs hot (thermal status moderate or worse). Previews that fail to load are skipped for the rest of the run and make it report that pictures remain. `BATCH` is 8, about four seconds of pictures at 450 ms each.
- A post that plays is first stored from its still like any other. `drainFrames`, in an idle-only job, later replaces that with still plus frames, one post at a time, each stored as it finishes, losing only the post under way if the job stops.
- A post whose frames cannot be read keeps its still analysis, and is retried at most a few times, once per run (the job is scheduled at every app start), so a source is not asked every night.
- Posts failing to be reached one after another point at the network: only the first counts against its post, and after `UNREACHABLE_STREAK` (3) the run stops and asks to run again soon.
- `settleStills` marks posts that do not play as complete, so a still image never waits for the idle-only job to count as tagged. The posts that play stay awaiting, ahead of the next batch.
- `writeThrough` stores what the session already analysed for posts kept since (for example a liked For You card), so pictures are never read twice. It wakes when `observeLibraryTotal` changes, which counts the Library only after a write to saved or liked posts, never after Recents' post writes or the backfill's own.

## Consequences

Indexing is slow by design. Loosening the duty cycle or the unreachable streak trades heat and provider load for speed.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfill.kt`: `LibraryVisionBackfill`, `drain`, `drainFrames`, `writeThrough`, `settleStills`, `BATCH`, `UNREACHABLE_STREAK`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomVisualSignatureRepository.kt`: `observeLibraryTotal`, `observeVisionChanges`, `predictedTags`
