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
