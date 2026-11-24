# 0152. Followed and saved-search collections are live, read-only feeds whose new-post marks move only when the results are actually shown

Status: Accepted

## Context

Followed creators and saved searches show posts that were never saved. They are fetched from sources, may fail partly, and need "new post" marks that must not move because of a failed load, a refresh or a different sort.

## Decision

- Live feeds are session-owned and retained across navigation, including pagination and retry state (`SavedCodexSession`). `FollowedCodexViewModel` identifies its branches by follow membership and creator, so check or visit metadata never restarts an otherwise identical feed; creator pages are fetched and parsed off the main thread. A query collection owns live continuations, never durable Codex memberships.
- Live posts were never saved, so their post date stands in for save time when sorting (`liveCodexOrder`). Under the date sorts a live feed only appends: `LiveCodexFeedController` numbers each load (first page, load-more, retry) as a batch, and posts sort by batch, then by date inside it, so a later page never lands among posts already shown. A refresh starts over at batch zero, except for posts restored from branches whose refresh failed. Explicit sorts (watched, length, creator, source) order the whole loaded list. `LiveCodexFeedController` also provides stable branch order, bounded overlap and stale-result rejection.
- The Searches collection runs every saved search newest-first (`newestFirst`), whatever sort it was saved with, so its first page holds the posts the new-posts check counts; opening the saved search in Search keeps its saved sort.
- A refresh keeps the current pages until the new ones arrive. A branch whose refresh fails keeps its last good pages, shows its error, and stays due for a reload; Retry runs the refresh again. A reset or re-sort clears its related shelf because the anchor moves, and newly resolved durations refine the order quietly; live collections never promote shelf posts into themselves.
- `SavedSearchFeedLoader` is read-only: it never persists applied Search, Recents, statistics or new recommendation seeds.
- The app-open check (`SavedSearchNewPostsChecker`) runs the newest `MAX_CHECKED_SAVED_SEARCHES` searches, two at a time, newest-first whatever sort they were saved with (a random or popularity order shows older posts nothing uploaded). A search whose first page failed in any part contributes nothing, so a flaky source never counts its posts as new. The attempt is recorded after the check has run, so one cancelled by leaving the app can run again.
- `SavedSearchOpenMarks` marks a saved search's new posts seen only once Search reports results for the same query without error; an offline or failed load, or a query changed meanwhile, leaves the marks alone, and posts the results left out stay new. Only the first such report counts.
- The followed-creator filter sheet owns vertical scrolling, so author rows must not create a nested scroll surface (`FlowRow` there).

## Consequences

Marks reflect what the user was actually shown. A new live collection type needs its own branch identity that excludes volatile metadata.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedCodexSession.kt`: `SavedCodexSession`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/FollowedCodexViewModel.kt`: `FollowedCodexViewModel`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/LiveCodexFeed.kt`: `liveCodexOrder`, `LiveCodexFeedController`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchFeedLoader.kt`: `SavedSearchFeedLoader`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchesCodexViewModel.kt`: `SavedSearchesCodexViewModel`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchNewPosts.kt`: `SavedSearchNewPostsChecker`, `SavedSearchOpenMarks`, `newestFirst`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/FollowedCodexFilters.kt`: author filter rows

## Guarded by

- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/SavedSearchesCodexViewModelTest.kt`: `load more on one newest-first saved search appends below the posts already shown (ADR 0152)`, `a Top saved search's collection refreshes to the newest uploads, not its top-scored page (ADR 0152)`, `reopening a feed loaded before a check found new posts reloads the newest first page, keeping old pages and showing the error if that fails (ADR 0152)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/FollowedCodexViewModelTest.kt`: `refresh keeps the loaded posts while offline, shows the error, and Retry replaces them with the newest page (ADR 0152)`
