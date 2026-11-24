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
