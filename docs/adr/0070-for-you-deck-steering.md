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

## Consequences
Steering resets with the process.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/DeckPlanner.kt`: `DeckSteering`, `after`, `undone`, `nextBatch`
