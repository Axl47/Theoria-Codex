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
