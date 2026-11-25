# 0094. Library picture indexing is paced, thermally gated and bounded in retries

Status: Accepted

## Context

Indexing liked and saved posts runs on a phone: it must not heat it, hold the CPU, or hammer providers when the network is down. Whether a post plays is known on the device, and a model migration once marked every earlier analysis as awaiting frames.

## Decision

- `drain` runs in a job that waits for a charger, a battery that is not low and an unmetered network. It analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. It stops when done, when the model goes or will not load, or when the device runs hot (thermal status moderate or worse). Previews that fail to load, and posts whose preview host is rate limited (`reachable`, checked as each post is reached, so it is not loaded at all), are skipped for the rest of the run, paged past with `skip`, and make it report that pictures remain. A preview that failed while its host was reachable is stamped (`failPreviews`), and posts stamped within `PREVIEW_RETRY_MS` are not listed, the same backoff the hashing backfill uses (ADR 0111), so a broken preview is fetched once a day, not by each pass. `BATCH` is 8, about four seconds of pictures at 450 ms each.
- A post that plays is first stored from its still like any other. `drainFrames`, in a job with the same constraints that also waits for an idle device, later replaces that with still plus frames, one post at a time, each stored as it finishes, losing only the post under way if the job stops.
- A post whose frames cannot be read keeps its still analysis, and is retried at most a few times, once per run (the job is scheduled at every app start), so a source is not asked every night.
- Posts failing to be reached one after another point at the network: only the first counts against its post, and after `UNREACHABLE_STREAK` (3) the run stops and asks to run again soon.
- `settleStills` takes posts that do not play out of the frame queue, so a still image never waits for the idle-device job to count as finished. The posts that play stay awaiting, ahead of the next batch.
- `observeVisionProgress` counts a post that plays as tagged once its first analysis is stored (from its still, or none when it has no still), and reports how many of those still wait for their frames (`awaitingFrames`); `finished` leaves them out. Settings shows the tagged count, so a Library of videos the idle-device job has not reached yet still reads as tagged, from their previews.
- `writeThrough` stores what the session already analysed for posts kept since (for example a liked For You card), so pictures are never read twice. It wakes when `observeLibraryTotal` changes, which counts the Library only after a write to saved or liked posts, never after Recents' post writes or the backfill's own.

## Consequences

Indexing is slow by design. Loosening the duty cycle or the unreachable streak trades heat and provider load for speed.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfill.kt`: `LibraryVisionBackfill`, `drain`, `drainFrames`, `writeThrough`, `settleStills`, `BATCH`, `UNREACHABLE_STREAK`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/RoomVisualSignatureRepository.kt`: `observeLibraryTotal`, `observeVisionChanges`, `predictedTags`, `observeVisionProgress`
- `app/src/main/java/com/theoriacodex/app/settings/PictureTagsEntry.kt`: `libraryProgress`

## Guarded by

- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfillTest.kt`: `a device running warm stops the picture pass before its next batch and asks for another run (ADR 0094)`, `a model that will not load asks for another run while it is installed, and none once it is gone (ADR 0094, 0097)`, `previews that fail to load are stamped and left for a later day, so the older Library is still indexed (ADR 0094, 0097)`, `posts on a host that is holding requests off are not loaded, and a later run tries them again (ADR 0094)`, `a playing post is stored from its still first, then upgraded with its frames, each repeated scene once (ADR 0094)`, `adding a post to a tagged Library analyses only that post (ADR 0094)`, `stopping the frame upgrade keeps the posts already upgraded and loses only the one under way (ADR 0094)`, `frames that cannot be read leave the still analysis standing, tried once a run and a few times at most (ADR 0094, 0097)`, `posts failing to be reached one after another stop the run and count once, and posts that stopped playing are set aside (ADR 0094)`, `a device running warm stops the frame upgrade and asks for another run (ADR 0094)`, `while the app runs, analyses awaiting frames whose post does not play stop waiting (ADR 0094, 0097)`, `a picture the session already analysed is stored once its post is kept, without reading it again (ADR 0094)`
- `core-data-android/src/test/kotlin/com/theoriacodex/data/android/room/RoomVisualSimilarityStorageTest.kt`: `a still analysis awaiting frames is listed until its frames are read or its reads run out (ADR 0017, 0094)`, `a post only the tagger saw still waits for its hash, and a post the tagger cannot read is not retried (ADR 0017, 0094)`, `the library total follows saves and likes, and vision writes are announced (ADR 0094)`, `a preview that failed to load waits a day before it is listed for hashing or tagging again (ADR 0094)`
- `app/src/test/java/com/theoriacodex/app/AppJobTest.kt`: `both Library picture passes wait for a charger, a battery that is not low and an unmetered network, and the frame pass also for an idle device (ADR 0094)`
- `app/src/test/java/com/theoriacodex/app/settings/PictureTagsProgressTest.kt`: `videos tagged from their preview count as tagged while their frames wait (ADR 0094)`
- Left to prose: the half duty cycle and `BATCH` size (8, from 450 ms a picture) are heat budgets measured on a phone, not values a host test can judge, and the rest between batches runs on a real monotonic clock; the thermal threshold sits in the app's `appPictures` wiring, which no test here builds, so it is unguarded; "once per run" rests on the job being scheduled at every app start; that the library total ignores Recents' and the backfill's own post writes is not exercised.
