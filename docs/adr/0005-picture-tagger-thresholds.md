# 0005. Predicted picture tags are a separate tag source with per-use score thresholds

Status: Accepted

## Context

The opt-in on-device tagger predicts Danbooru tags from thumbnails. The visual-similarity spike measured precision and recall against real Danbooru tags on 360 px thumbnails.

## Decision

- `PredictedTag` is a second tag source beside provider tags and is never shown as a tag the post was given; each use applies its own threshold.
- `PredictedTagUse.HIDE` favours recall (a miss shows unwanted content, a false hit skips one card): general 0.35 (precision 0.63, recall 0.55), character 0.5 (0.89 / 0.82). `EVIDENCE` favours precision (it seeds For You searches and ranks save suggestions): general 0.5 (0.71 / 0.46), character 0.7 (0.93 / 0.78); tags then weigh their score.
- `STORED_FLOOR` 0.35 is the lowest any use needs.
- While the model is absent, `PictureTags.ready` is false, `library` empty, `known` empty and `look` returns `Blind`, so features behave as before the tagger existed. `known` never analyses; `look` does not remember anything other than `Seen`, so asking again retries.
- `library` is an observable index of the Library's stored predictions plus the vocabulary's base rates, and changes only when the Library is indexed. `known` reads that stored index and the session's own analyses; a card analysed this session is visible to `known` but never updates the `library` flow.

## Consequences

Changing a threshold needs re-measurement; the stored floor must stay at or below every use's minimum.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/vision/PredictedTag.kt`: `PredictedTag`, `PredictedTagUse`, `STORED_FLOOR`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/vision/PictureTags.kt`: `PictureTags`, `PictureLook`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/vision/PredictedTagTest.kt`: `each use counts a tag from its measured threshold and not just below it (ADR 0005)`, `the stored floor is 0_35, at or below every use's threshold (ADR 0005)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/vision/PictureTagsTest.kt`: `without a tagger nothing is ready, known or seen (ADR 0005)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/PictureTaggerTest.kt`: `while the model is absent the tagger answers Unavailable and knows nothing (ADR 0005)`
- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/LibraryVisionBackfillTest.kt`: `look remembers only a seen picture, so an unreachable one is asked again (ADR 0005)`, `a picture analysed this session is known but never reaches the library flow (ADR 0005)`, `known with a ready model answers null for an unanalysed picture without reading or analysing it (ADR 0005)`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/vision/PictureTags.kt`: `fun known(id: PostId): List<PredictedTag>?`
- Left to prose: never showing a prediction as a tag the post was given is a UI rule; the thresholds' precision and recall need re-measurement, not a check.
