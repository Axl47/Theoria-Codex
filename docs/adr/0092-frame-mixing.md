# 0092. Several pictures of a post fold into one analysis by weighted top-third scores

Status: Accepted

## Context

Videos and animations are tagged from several frames, but the thresholds were calibrated on single pictures, and one-frame flashes should not create tags.

## Decision

- `distinctPictures` folds each near-copy of an earlier picture (perceptual hashes within the same-picture distance) into it, so a still scene costs one analysis but keeps its share of the post. Pictures too flat to hash (black or solid frames) are dropped, unless all are, when the first stands for them all.
- `combineScores` gives each output the mean over the top third of the pictures by that score, counting each picture by its weight. A tag must hold for about a third of a video to score high. A single picture keeps its own scores, so the calibrated thresholds mean what they did.
- `combineEmbeddings` returns the weighted mean of unit-length embeddings, at unit length.

## Consequences

Changing the one-third rule shifts every video's tags and requires recalibrating thresholds.

## Applies to

- `vision-kit/src/main/kotlin/com/theoriacodex/app/vision/FrameMix.kt`: `distinctPictures`, `combineScores`, `combineEmbeddings`

## Guarded by

- `vision-kit/src/test/kotlin/com/theoriacodex/app/vision/FrameMixTest.kt`: `near copies fold into the first and flat pictures drop (ADR 0092)`, `when every picture is flat the first stands for them all (ADR 0092)`, `a single picture keeps its own scores (ADR 0092)`, `each score is the mean of its top third of weighted pictures (ADR 0092)`, `the embedding is the weighted mean direction at unit length (ADR 0092)`, `a heavier picture pulls the embedding toward its direction (ADR 0092)`
- Left to prose: that a tag must hold for about a third of a video, and that the calibrated thresholds keep their meaning, are judgements about the model's output, not checkable here.
