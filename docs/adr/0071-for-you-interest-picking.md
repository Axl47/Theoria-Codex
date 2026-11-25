# 0071. For You interests are weighed once per source and picked from proven, co-occurring tags

Status: Accepted

## Context
For You lanes seed from a profile's Likes, which can come from other sources with different tag vocabularies, and callers pick again when tags turn out unavailable on a source.

## Decision
- `ForYouInterests.prepare` weighs every candidate once so repeated picks (`ForYouInterestPicker.pick`) repeat no work. Candidates are the tags of what was liked (possibly other sources' Likes translated), kept on the source, or chosen; one candidate per normalized key, in the spelling a Like gave it.
- Once the source has history, a candidate leads a lane only when proven there or, spelled by `translate`, on another source sharing its vocabulary. Before history every informative candidate may lead. Picture-predicted candidates lead only when `pictureAffinity` proves them.
- Tags that keep appearing together on Likes stay one interest (`togetherEnough`: at least two shared documents and `PAIR_SHARE` of the more common one's). Only leads can pair and only anchors a pick tries are paired, because counting every pair up front grows with the square of a post's tags.
- Ties, such as every tag before any history, go to the most liked tag. `pick` returns up to `maxInterests` strongest, then the best guess to explore; `random` varies each score by up to a fifth so leads change between Mixes. Tags in `unavailable` are never candidates.

## Consequences
A character followed on one source can seed another.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/ForYouInterests.kt`: `ForYouInterests`, `prepare`, `ForYouInterestPicker`, `pick`, `togetherEnough`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/ForYouInterestsTest.kt`: `a liked tag and its kept spelling are one candidate (ADR 0071)`, `a saved tag leads a lane without any Like carrying it (ADR 0071)`, `lanes rank by lift, skip watched-everywhere, unlifted and image-text tags, and explore a guess (ADR 0071)`, `a tag proven on Pixiv leads a lane on a booru that has history of its own (ADR 0071)`, `interests are bounded and use source normalization (ADR 0071)`, `before any history, text on the image and tags about the file never lead (ADR 0071, 0072)`, `a tag predicted on many kept pictures leads a lane, compared with what the profile watches (ADR 0071, 0087)`, `too few analysed kept posts, or text on the image, prove nothing (ADR 0071)`, `separate interests stay together rather than creating unrelated pairs (ADR 0071)`, `tags pair only when they share most of the more common one's Likes and more than a single Like (ADR 0071)`, `with equal scores the most liked tag leads (ADR 0071)`, `a higher score leads a tag that is liked more (ADR 0071)`, `an unavailable tag is not paired in either (ADR 0071)`, `tags the source cannot serve are never picked, as lead or as guess (ADR 0071)`
- Left to prose: that `prepare` weighs each candidate once is unguarded because it is a cost choice with identical picks; how far `random` moves a score is unguarded because a test could pin it only by restating the constant.
