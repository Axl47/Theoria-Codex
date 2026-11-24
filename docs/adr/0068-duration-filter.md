# 0068. Duration filters reveal posts in stable order and schedule only the demand that matters

Status: Accepted

## Context
A duration filter hides a post until its length is known, and lookups finish out of order and over the network. Cards on screen must not change position when a late answer arrives.

## Decision
- `DurationReveal` turns out-of-order decisions into an append-only presentation: posts before `frontier` are revealed, and a post decided after being passed joins at the old frontier (`LateDurationArrival`), below what is showing. `DEFAULT_DURATION_REVEAL_WINDOW` lets a few later posts be decided before a stuck post is passed. A replaced post list starts over.
- `DurationDemandBacklog` keeps owned demand beyond a full work queue, with priority lanes so a large library is not rescanned per completion.
- `DurationDemandScheduler.takeNext` runs only the filter's demand during a scroll, since the filter hides posts until decided.
- `reconcileDurationDecisions` retains settled route values beyond the coordinator's bounded LRU snapshot.
- `nextMp4TopLevelBoxOffset` walks MP4 top-level boxes by their sizes to find `moov` past the window, so long videos need no fixed-size guess at the tail; `hasTypeAt` compares bytes in place because it runs at every scanned offset.

## Consequences
Related shelves and restored scroll positions must account for late arrivals sitting below higher canonical indices.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/DurationReveal.kt`: `DurationReveal`, `LateDurationArrival`, `advanceDurationReveal`, `DEFAULT_DURATION_REVEAL_WINDOW`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/DurationDemandBacklog.kt`: `DurationDemandBacklog`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/DurationDemandScheduler.kt`: `takeNext`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/DurationDecisionProjection.kt`: `reconcileDurationDecisions`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/Mp4DurationParser.kt`: `nextMp4TopLevelBoxOffset`, `hasTypeAt`
