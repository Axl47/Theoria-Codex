# 0070. The For You deck deals deterministically from weighted lanes and steers from this session's swipes only

Status: Accepted

## Context
The deck mixes interest lanes, injected cards after a like or save, and posts that exist on several sites. It must not repeat a picture and must stay cheap with a large seen history.

## Decision
- `nextBatch` deals pending cards first, then lanes in turn by weight (smooth weighted round-robin credit), deterministically; a lane that runs out gives its turns to the others, and posts another lane or injection already gave are skipped so a turn always yields a card.
- `seen` is read as is and the batch's own set is separate, so a large history is never copied.
- `copiesOf` names posts holding the same file or picture: a post is seen when any copy is, and dealing it uses all copies.
- `DeckSteering.after`: every `SKIP_RUN`th skip or hide in a row halves a lane's weight down to `MIN_WEIGHT`; a like or save ends the run and restores it. Steering is in memory only. Swipes are also logged per profile through `ForYouVerdictRepository`, but nothing reads that log into steering.
- `undone` restores prior steering and drops cards the undone post asked for, keeping credit.
- `cooled` names a lane below full weight, which Filters' Mix list dims; `warmed`, applied by Filters' Reset, returns every lane to full weight and ends its run, keeping injected cards and credit.

## Consequences
Steering resets with the process.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/DeckPlanner.kt`: `DeckSteering`, `after`, `undone`, `cooled`, `warmed`, `nextBatch`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/DeckPlannerTest.kt`: `lanes take turns and each card names its lane (ADR 0070)`, `a post two lanes hold is dealt once and a seen post never (ADR 0070)`, `repeated skips halve a lane until a like restores it (ADR 0070)`, `injected cards come first and undo drops the ones its post asked for (ADR 0070)`, `one picture is dealt once, and not at all when a copy was seen (ADR 0070)`, `a like between skips ends the run and weight never falls below the floor (ADR 0070)`, `undo restores the steering from before the swipe and keeps this deal's credit (ADR 0070)`, `hides and skips share one run that halves a lane (ADR 0070)`, `an injected picture's copies are not dealt again from a lane (ADR 0070)`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/recommend/ForYouDeckViewModelTest.kt`: `a lane cooled by skips reads as cooled until its lanes are warmed (ADR 0070)`
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/DeckPlanner.kt`: `seen: (PostId) -> Boolean,`
- Left to prose: that steering lives in memory only and nothing reads the swipe log back into it is unguarded, since only a text rule over every reader of `ForYouVerdictRepository` could check it.
