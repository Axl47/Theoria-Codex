# 0225. Library's live feeds refresh on pull with Series' pill

Status: Accepted

## Context

Followed and Searches are live feeds (ADR 0152) that already had a refresh path (`LiveCodexFeedOwner.refresh`) but no
gesture reached it. Series already had a pull pill (ADR 0219), and the two screens should look and behave the same. A
feed's `loading` is also true while it loads its first page or more pages, so it cannot say when a refresh is running.

## Decision

The pull box and its pills live in ui-kit (`PullRefreshBox`, `RefreshProgressPill`, `RefreshMessagePill`); Series keeps
only its labels and check states. Library wraps them in `LiveCodexRefreshBox`, which refreshes every live feed it is
given and shows "Checking" while any of them reports `LiveCodexFeedState.refreshing`. Only a reload (a refresh, or a
Retry of a failed refresh) sets `refreshing`, so a first load or a load-more never pins the pill.

The Saved page (Library's Saved section, and the Saved collection opened from Codices) pulls every live feed its
shelves have shown; inside Followed or Searches a pull refreshes that feed. A failed pull keeps the posts and reports
through the existing "Couldn't refresh" state, so the pill shows no result. Each finished reload counts up
`LiveCodexFeedState.refreshes`, and the shelf row or collection grid then scrolls to its start
(`ShowNewestAfterRefresh`, which saves the last count it handled with the list's scroll state, so a shelf that
refreshed while scrolled off the Saved page still starts at its newest posts when it returns): keyed lazy lists keep the old first card anchored, which would leave the new posts just
out of view. Only a finished refresh scrolls, so new-post marks reordering a shelf never fights the Viewer's return.
A collection's empty state scrolls, so a pull still reaches a live feed that is empty or filtered to nothing.
Ordinary Codices and the Codices list hold
only saved posts, so they have nothing to refresh and no pull.

## Consequences

A new live collection gets pull to refresh by passing its owner to `LiveCodexRefreshBox`.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/components/PullRefresh.kt`: `PullRefreshBox`, `RefreshProgressPill`, `RefreshMessagePill`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/LiveCodexRefresh.kt`: `LiveCodexRefreshBox`, `ShowNewestAfterRefresh`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/LiveCodexFeed.kt`: `LiveCodexFeedState.refreshing`, `LiveCodexFeedState.refreshes`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailScreen.kt`: `CodexDetailScreen`
- `app/src/main/java/com/theoriacodex/app/ui/routes/SavedCodexRoute.kt`: `SavedCodexRoute`

## Guarded by

- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/LiveCodexRefreshTest.kt`: `pulling a live collection refreshes its feed, shows Checking until the refresh ends, then shows the newest post (ADR 0225)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/LiveCodexRefreshTest.kt`: `an empty live collection still takes a pull (ADR 0225)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/LiveCodexRefreshTest.kt`: `pulling the Saved shelves refreshes every live feed on them (ADR 0225)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/FollowedCodexViewModelTest.kt`: `only a refresh reports refreshing or counts as one, never the first load, a load more or a reset (ADR 0225)`
- `feature-library/src/test/kotlin/com/theoriacodex/app/codex/LiveCodexRefreshTest.kt`: `a shelf that refreshed while off screen shows its newest posts when it comes back (ADR 0225)`
- `app/src/androidTest/java/com/theoriacodex/app/journeys/LibraryPullToRefreshJourneyTest.kt`: `pullingTheSavedPageAndTheSearchesCollectionShowsNewUploads_adr_0225`
