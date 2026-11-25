# 0097. The picture tagger remembers results per session and never leaves answers behind a deleted model

Status: Accepted

## Context

The model is an opt-in download that can be deleted at any time, and results come from For You looks (session only) and Library indexing (stored). Stale answers after a delete, or half-stored results, would misreport tags.

## Decision

- With the model absent, `library` is empty and `analyze` answers `Unavailable`, so every consumer behaves as before the tagger existed.
- `commits` is a mutex held while results are stored and while a deleted model's are forgotten, so none outlive the delete. `store` and `storeFrames` return false and store nothing once the model was deleted. A result computed while the model is deleted is not remembered.
- `analyze` remembers per session when asked; a failed load is not remembered. Library indexing does not remember, since it stores. `analyzeFrames` is never remembered.
- What the Library stored wins over the session's look, because it may have read a playing post's frames, which a session look never does.
- `library` reads stored rows at once, then only after stored analyses have been quiet for `LIBRARY_SETTLE_MS`. It waits on `observeVisionChanges`, which runs no query, and reads and decodes with `predictedTags` once the writes settle, so a backfill batch costs one read of the stored tags rather than one per write.
- `analyzePictures` runs one picture at a time so a For You look waits for at most one. Only a loaded model failing on a picture makes it `Unanalysable`; a model or vocabulary that fails to load (low memory) leaves it `Unavailable` for a later try.
- `store` leaves out pictures that could not be read now. `playing` settles the still analyses of posts that do not play, in one write. A failed `storeFrames` leaves the still analysis standing and counts against the post's frame reads, for good when another try would repeat it.
- `SessionAnalyses` keeps the most recent `LIMIT` results and is read and written from any thread.

## Consequences

Anything that stores tag results must go through `commits`. A new result type must say whether retrying could change the answer.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureTagger.kt`: `PictureTagger`, `analyze`, `analyzeFrames`, `store`, `storeFrames`, `playing`, `forget`, `SessionAnalyses`, `LIBRARY_SETTLE_MS`

## Guarded by

- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PictureTaggerTest.kt`: `while the model is absent the tagger answers Unavailable and knows nothing (ADR 0005, 0097)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfillTest.kt`: `once the model is deleted nothing is stored or remembered, even a picture read before the delete (ADR 0097)`, `deleting the model forgets what it predicted, stored or remembered (ADR 0097)`, `a delete that lands during a write waits for it, then forgets what it wrote (ADR 0097)`, `a malformed vocabulary leaves the store Ready and the picture Unavailable for a later try (ADR 0097, 0101)`, `only a session look at a still is remembered, not library indexing, a failed load or a frame read (ADR 0097)`, `what the Library stored for a post wins over this session's look at its still (ADR 0097)`, `stored tags are read once the Library settles, not once per write (ADR 0097)`, `a model that cannot load leaves a picture Unavailable and one that fails on it makes it Unanalysable (ADR 0097)`, `previews that fail to load are skipped, so the older Library is still indexed, and a later run tries them again (ADR 0094, 0097)`, `while the app runs, analyses awaiting frames whose post does not play stop waiting (ADR 0094, 0097)`, `frames that cannot be read leave the still analysis standing, tried once a run and a few times at most (ADR 0094, 0097)`, `the session remembers only its most recent results (ADR 0097)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PictureModelHolderTest.kt`: `concurrent uses run one at a time on one model (ADR 0096, 0097)`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/VisualSignatureRepository.kt`: `level = RequiresOptIn.Level.ERROR`, `annotation class VisionCommit`, `@VisionCommit suspend fun putVision(entries: List<PictureVision>)`, `@VisionCommit suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean)`, `@VisionCommit suspend fun unwantFrames(postIds: List<PostId>, modelId: String)`, `@VisionCommit suspend fun clearVision()`
- `scripts/tests/test_check_architecture.py`: `test_reports_vision_results_written_outside_the_tagger_adr_0097`
- Left to prose: the compiler makes every caller of a marked writer opt in, an override pass the marker on, and the rule admits the opt-in only in `PictureTagger`, while the interface and the Room store may only pass the marker on; the Room DAO is package-private, so other modules cannot write around it. Unguarded: a writer added to the interface without the marker, a helper inside `PictureTagger` that exposes a write without taking `commits` (the class opts in as a whole), a write through the DAO from inside the Room package, and that each write inside `PictureTagger` sits under `commits`. That `analyzePictures` takes the model once per picture rather than once per post, and that `SessionAnalyses` is safe from any thread, are not exercised.
