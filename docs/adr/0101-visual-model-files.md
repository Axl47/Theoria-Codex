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
- `TagVocabulary` reads `selected_tags.csv` (`tag_id,name,category,count`), one row per model score in output order. Categories are 0 general, 4 character, 9 rating. Ratings are dropped from predictions because no tag list in the app uses them. Counts are Danbooru post counts, so a tag's base rate is its count over all posts (the four ratings summed). `PictureTagger` reads the file lazily on first use, not the store; a malformed file throws there and the analysis is reported as unavailable or unanalysable, while the store still reports `Ready`.

## Consequences

Publishing a new model means a new file set, new id and pins; existing releases stay untouched.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/VisualModelStore.kt`: `ModelFile`, `VisualModelSpec`, `VisualModelStore`, `ModelState`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/TagVocabulary.kt`: `TagVocabulary`, `predictions`, `read`
- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/PictureTagger.kt`: `PictureTagger`
