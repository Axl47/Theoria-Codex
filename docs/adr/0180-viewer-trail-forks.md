# 0180. A Viewer level runs at most one trail fork and cancels it when the user pages away

Status: Accepted

## Context
Trail chips and the info sheet's tags both start a new search trail from the post on screen. The fork loads while the chrome may hide or the sheet may close, and the user may page to another post meanwhile.

## Decision
- `ViewerTrailForks` is shared by the level's chips and info sheet; at most one fork loads at a time (`open` returns false while `busy`). A fork runs in `scope`, which lives as long as the level (`close`), so hiding the chrome or closing the sheet leaves it running.
- Paging to another post cancels it: `leave` as soon as a swipe heads there, `follow` once it lands; a fork must not open on a post other than the one it was asked from.
- The job is started lazily and only once it is recorded as `job`, because an immediate dispatcher may run it to its end inside `launch`. A cancelled fork may finish after the next one started, so it clears `busy` and `failed` only if it is still the current `job`.
- A chip that is a tag already searched at this level is not offered again, and a tag on nearly every post of the level narrows nothing here (such as a trail's own filter tag), so `feedTagShare` filters it out of the chips.
- `trailTagQuery` builds the fork's query with the tapped tag first, then the include groups of the level it came from (the tag is not added twice), then the exclusions that fit. The tag leads because a term limit keeps the leading groups; a single-source trail applies the source's limit through `withinTermLimit`, and each source of a Unified trail does the same with its own limit. A level whose mode differs from the trail's mode contributes nothing, because another source's terms may name tags the trail's source spells differently. Terms are matched with `PostTagSelection`, as the source matches them, so a typed term such as `artist:foo` is not taken for a plain `foo`. The query asks for animated posts when the level's query did or the level shows only animated posts.
- `trailVisibilityFilters` keeps the parent's phone-side filters, lets a tag trail's own query decide media and dates, and resets the animated length filter to `AnimatedDurationRange.Full`: a fork's lengths are not known yet and an unknown length would hide every post.
- `tagTrailPager` pages through the read-only saved-search loader, which records nothing in Search, Recents or statistics. A failed page throws so the feed keeps its posts and the next call retries what failed; a request error counts as failed, a source failing while others page on does not. `creatorTrailPager` keeps only posts from the creator's own source. `similarTrailPager` gives one page without the seed and never pages.
- `TrailFeed.start` loads the first page and up to `FIRST_PAGE_LIMIT` (3) pages while the level's filters leave nothing visible besides the post the fork was opened from or a copy of its file (`FileMirrorIndex.isCopy`, checked from both sides because the fork's own listings may not be learned yet), and the fork's chip scrolls into view while it loads or fails. `TrailOpener.openTrail` reports `TrailOutcome`: a fork with no other posts is `Empty` ("No other posts"; a creator page's tag chip turns off), and one whose source is unavailable or whose first page failed is `Failed` ("Couldn't load"; the chip stays usable). A fork whose results hold only that post narrows nothing, which happens quickly on gallery sources such as Hitomi, where a post is a whole gallery and a few tags together can match only it, so it counts as empty. Relaxing the query instead on such a dead end is backlog work. 3 is Search's own automatic page limit, so a filtered fork gives up as soon as a feed would. A trail keeps no related shelf, since Similar is itself a trail.
- `openTrail` opens a fork only over the screen it was asked from. `stillOver` is checked right before navigating and reads the Viewer's own selection, because paging cancels a fork from an effect that can run only after the fork finished. When the user has moved on, the opener ends in a `CancellationException`, since there is no failure to report. The fork's feed is closed in a `finally`, including on cancellation, because a fork that never opened has no entry to close it.
- A fork saves no launch context (`saveContext = false`): the Viewer it covers keeps its own for restoration. A fork from a Viewer starts from that level's filters; one from a grid starts from `SearchVisibilityFilters()`.
- `historyOrigin` stores a trail's watched posts in History as a search. History and profile backups keep the origin by name, and a build from before trails cannot read a name it does not know.
- Each Viewer entry records its ancestors (labels and entry ids) in its own saved state, so crumbs survive process death, when the levels underneath are not composed. A fork restored after process death has no feed: it shows its saved window without paging and closes back to the level under it (`onRestorationUnavailable`).
- Dismissing a fork pops only its own entry and leaves Search's launch context to the root; its feed pages the TRAIL stream, which no shell-wide feed owner serves. Only a root Viewer's entry holds its card's origin, and releases it only when it leaves the back stack ([0220](0220-screens-leave-as-one-opaque-unit.md)): a covered root keeps it for when the user comes back down the trail and closes it. A trail opened from a full screen stacks on no Viewer, so it holds an empty origin of its own, and the Viewer under that screen gets its card back once the trail leaves. A trail level closes with a plain fade, never a card return.

## Consequences
Forks cannot be doubled or open on the wrong post. Any new fork entry point must go through the level's `ViewerTrailForks`.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailForks.kt`: `ViewerTrailForks`, `open`, `follow`, `leave`, `close`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerTrailRow.kt`: `ViewerTrailRow`, `rememberViewerTagFork`
- `app/src/main/java/com/theoriacodex/app/ui/trails/TrailSpecs.kt`: `trailTagQuery`, `trailVisibilityFilters`, `tagTrailPager`, `creatorTrailPager`, `similarTrailPager`
- `app/src/main/java/com/theoriacodex/app/ui/trails/TrailFeed.kt`: `TrailFeed`, `start`, `FIRST_PAGE_LIMIT`
- `app/src/main/java/com/theoriacodex/app/ui/trails/TrailOpening.kt`: `openTrail`, `stillOver`, `TrailHandoffViewModel`
- `app/src/main/java/com/theoriacodex/app/ui/trails/ViewerTrailLevel.kt`: `ViewerLevel`, `recordAncestors`, `forLevel`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRouteWorkflow.kt`: `historyOrigin`

## Guarded by
- `app/src/test/java/com/theoriacodex/app/ui/trails/TrailFeedTest.kt`: `a step opens only with a post besides the one it was opened from or its mirrors (ADR 0180)`
- `feed-kit/src/test/kotlin/com/theoriacodex/app/post/FileMirrorIndexTest.kt`: `a listing not learned yet is a copy of a known post with the same file (ADR 0180)`
