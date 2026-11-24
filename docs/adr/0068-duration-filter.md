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

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/media/DurationRevealTest.kt`: `later decisions wait behind an undecided post (ADR 0068)`, `a post stuck behind a full window is passed and joins the bottom when it arrives (ADR 0068)`, `a failed post that later gains a duration also joins the bottom (ADR 0068)`, `a replaced post list starts over (ADR 0068)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/DurationDemandBacklogTest.kt`: `the backlog serves the strongest lane first and a stronger demand moves a key up (ADR 0068)`, `releasing a key's strongest demand returns it to the lane of the demand left (ADR 0068)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/DurationDemandSchedulerTest.kt`: `active scrolling pauses everything except an active filter (ADR 0068)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/DurationDecisionProjectionTest.kt`: `a settled value stays after the bounded snapshot drops it (ADR 0068)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/Mp4DurationParserTest.kt`: `next box offset skips the media data and stops at moov or for other containers (ADR 0068)`
- Left to prose: that the backlog spares a large library a rescan per completion, and that `hasTypeAt` compares bytes in place, are unguarded: they are cost choices with identical results, so no test can observe them.
