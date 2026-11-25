# 0152. Followed and saved-search collections are live, read-only feeds whose new-post marks move only when the results are actually shown

Status: Accepted

## Context

Followed creators and saved searches show posts that were never saved. They are fetched from sources, may fail partly, and need "new post" marks that must not move because of a failed load, a refresh or a different sort.

## Decision

- Live feeds are session-owned and retained across navigation, including pagination and retry state (`SavedCodexSession`). `FollowedCodexViewModel` identifies its branches by follow membership and creator, so check or visit metadata never restarts an otherwise identical feed; creator pages are fetched and parsed off the main thread. A query collection owns live continuations, never durable Codex memberships.
- Live posts were never saved, so their post date stands in for save time when sorting (`liveCodexOrder`). `LiveCodexFeedController` provides stable branch order, bounded overlap and stale-result rejection. A reset or re-sort clears its related shelf because the anchor moves, and newly resolved durations refine the order quietly; live collections never promote shelf posts into themselves.
- `SavedSearchFeedLoader` is read-only: it never persists applied Search, Recents, statistics or new recommendation seeds.
- The app-open check (`SavedSearchNewPostsChecker`) runs the newest `MAX_CHECKED_SAVED_SEARCHES` searches, two at a time, newest-first whatever sort they were saved with (a random or popularity order shows older posts nothing uploaded). A search whose first page failed in any part contributes nothing, so a flaky source never counts its posts as new. Each search is stamped as attempted when its request starts (ADR 0019), so one cancelled by leaving the app waits out the interval rather than running again on every return.
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
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchNewPosts.kt`: `SavedSearchNewPostsChecker`, `SavedSearchOpenMarks`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/FollowedCodexFilters.kt`: author filter rows
