# 0101. Model files are pinned by SHA-256, renamed into place only when verified, and removed on any failure

Status: Accepted

## Context

The tagger model is an opt-in download. Only the exact published bytes may ever be loaded, and an absent model must hold no space.

## Decision

- `ModelFile` pins size and SHA-256. `VisualModelStore` streams to a part file while hashing and renames it into place only after size and hash match, so a file in place was verified when it was downloaded. At startup the store accepts a model as `Ready` when each file's length matches; it does not rehash. A mismatch is reported as `ModelState.Failed(mismatch = true)`, separate from network failure.
- A failed download deletes everything it left, and a store that starts without a ready model deletes what an interrupted download left, so a failure or absent model never holds space.
- A blocking read cannot see cancellation, so a child watcher cancels the call at once, which throws.
- `VisualModelSpec.id` names the model in stored analyses; a new export gets a new id (its bytes and hash differ) and re-analyses everything. The v3 model is WD ViT tagger v3 (SmilingWolf, Apache-2.0), exported with both outputs and dynamic-range int8 weights by `tools/phone-bench/export_models.py` on the `spike/phone-bench` branch (litert-torch 0.10 `full_dynamic_recipe`, jax < 0.9).
- `delete` stops a download, removes the files and runs `onDeleted`, which forgets what the model predicted; nothing else in the app changes while the model is absent. Files live in the app's no-backup storage.
- `TagVocabulary` reads `selected_tags.csv` (`tag_id,name,category,count`), one row per model score in output order. Categories are 0 general, 4 character, 9 rating. Ratings are dropped from predictions because no tag list in the app uses them. Counts are Danbooru post counts, so a tag's base rate is its count over all posts (the four ratings summed). `PictureTagger` reads the file lazily on first use, not the store; a malformed file throws there and the picture is `Unavailable` for a later try, never stored as unanalysable, while the store still reports `Ready`.

## Consequences

Publishing a new model means a new file set, new id and pins; existing releases stay untouched.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/VisualModelStore.kt`: `ModelFile`, `VisualModelSpec`, `VisualModelStore`, `ModelState`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/TagVocabulary.kt`: `TagVocabulary`, `predictions`, `read`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureTagger.kt`: `PictureTagger`

## Guarded by

- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/VisualModelStoreTest.kt`: `download verifies both files, moves them into place and ends Ready (ADR 0101)`, `bytes that are not the pinned ones fail as a mismatch and leave no file behind (ADR 0101)`, `a download that fails on its second file leaves neither file behind (ADR 0101)`, `a file longer than pinned fails as a mismatch (ADR 0101)`, `an HTTP error fails without calling the bytes a mismatch (ADR 0101)`, `a failed download can be started again (ADR 0101)`, `a store over a folder holding both verified-size files starts Ready (ADR 0101)`, `files of the pinned sizes start Ready without being hashed again (ADR 0101)`, `a file cut short by an interrupted download does not start Ready (ADR 0101)`, `a folder holding only one file starts Absent and is cleared (ADR 0101)`, `delete after a download removes the folder and reports it (ADR 0101)`, `delete during a download stops it and nothing is left or restored (ADR 0101)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/TagVocabularyTest.kt`: `predictions keep general and character tags above the floor, strongest first (ADR 0101)`, `quoted names keep their commas and doubled quotes (ADR 0101)`, `base rate is the count over the four ratings' counts and excludes the rating tags themselves (ADR 0101)`, `a malformed file is rejected (ADR 0101)`, `scores must match the vocabulary's size (ADR 0101)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfillTest.kt`: `a malformed vocabulary leaves the store Ready and the picture Unavailable for a later try (ADR 0097, 0101)`
- Left to prose: that a new export gets a new `VisualModelSpec.id` is release discipline (a test pinning today's hashes would only restate them); the production store's no-backup root and its `onDeleted = tagger.forget()` hook are wiring in `appPictures`, which no test here builds, so they are unguarded (the tests wire `forget` to the store the same way); the export recipe is history.
