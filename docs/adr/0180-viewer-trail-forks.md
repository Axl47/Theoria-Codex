# 0180. A Viewer level runs at most one trail fork and cancels it when the user pages away

Status: Accepted

## Context
Trail chips and the info sheet's tags both start a new search trail from the post on screen. The fork loads while the chrome may hide or the sheet may close, and the user may page to another post meanwhile.

## Decision
- `ViewerTrailForks` is shared by the level's chips and info sheet; at most one fork loads at a time (`open` returns false while `busy`). A fork runs in `scope`, which lives as long as the level (`close`), so hiding the chrome or closing the sheet leaves it running.
- Paging to another post cancels it: `leave` as soon as a swipe heads there, `follow` once it lands; a fork must not open on a post other than the one it was asked from.
- The job is started lazily and only once it is recorded as `job`, because an immediate dispatcher may run it to its end inside `launch`. A cancelled fork may finish after the next one started, so it clears `busy` and `failed` only if it is still the current `job`.
- A chip that is a tag already searched at this level is not offered again, and a tag on nearly every post of the level narrows nothing here (such as a trail's own filter tag), so `feedTagShare` filters it out of the chips.

## Consequences
Forks cannot be doubled or open on the wrong post. Any new fork entry point must go through the level's `ViewerTrailForks`.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailForks.kt`: `ViewerTrailForks`, `open`, `follow`, `leave`, `close`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailRow.kt`: `ViewerTrailRow`, `rememberViewerTagFork`
