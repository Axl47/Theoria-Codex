# 0019. Follows and new-post marks are local, identity-keyed and rate-limited across restarts

Status: Accepted

## Context

Likes and creator follows are local actions with no remote bookmarks. New-post badges require remembering the last checked first page and what was shown.

## Decision

- A follow is identified by provider identity (`followKey`), never display name. Refreshes cannot resurrect a removed or replaced follow.
- Following marks the supplied visible posts of that creator as both latest and seen, so only later posts count as new; the follow list holds at most `MAX_FOLLOWED_CREATORS` (200).
- Undo of an unfollow restores the record unchanged, including marks, at its old position. It does nothing if the creator was followed again meanwhile (the newer record stays) or the follow list filled up in the meantime.
- Marks (latest ids, seen ids) are kept on the device for followed creators and saved searches; the first check with nothing seen only sets the baseline. Saved-search marks live with UI state because they are device-local and disposable.
- A failed check is stamped as an attempt, so a failing source waits out the check interval, restarts included.
- `CreatorFollowsViewModel.refreshIfStale` (the app-open check) refreshes only follows whose own last check or failed attempt, both saved with the follow, is older than the check interval. Follows a cancelled run never reached stay due.
- Checks are recorded in batches of `WRITE_BATCH_SIZE` (25) creators per settings write, so a large list neither rewrites settings per creator nor loses its progress when cancelled.
- `cancelAutomaticRefresh` stops the automatic check when the app leaves the foreground; a refresh the user started carries on. A refresh the user asks for during the automatic check replaces it and covers every follow rather than only the stale ones; the checks the automatic run already recorded stay. An automatic check never replaces a running refresh.
- `unfollowWithUndo` unfollows at once and its Undo restores the same record, so nothing the creator posted meanwhile counts as seen.
- `NewPostsCheckEffect` checks followed creators and saved searches whenever the app comes to the foreground (`repeatOnLifecycle(STARTED)`), once the first frame is drawn and only when the last check is stale (`checkIfStale`, `refreshIfStale`). Leaving the app cancels checks in flight (`cancelAutomaticRefresh`). Saved searches run read-only (`retry = false`), on the sources and settings their collection uses.
- `markingNewPostsSeen` marks the posts a live Followed or Searches collection shows (after the grid's display filters) as seen once its feed has drawn its first page, once per opening of the collection: loading a later page flips `rendered` again but must not mark its posts or late checks seen. A check that lands later, posts the filters hid, posts only in later pages, and posts the feed never showed (offline, a failed load) stay new. Any other collection has no new posts.
- `markViewedNewPost` marks a post seen once the Viewer shows it from the Followed or Searches collection, whether opened from a Saved shelf or the full collection (`newPostsCollection`); posts the Viewer never reached stay new, and other sessions have no new posts.

## Consequences

Never add server-side follows or notifications.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepository.kt`: `followKey`, `follow`, `restore`, `recordChecks`, `MAX_FOLLOWED_CREATORS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/NewPostMarks.kt`: `NewPostMarks`, `SavedSearchNewPosts`, `newPostsCheckDue`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `observeSavedSearchNewPosts`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModel.kt`: `refreshIfStale`, `cancelAutomaticRefresh`, `startRefresh`, `WRITE_BATCH_SIZE`, `unfollowWithUndo`
- `app/src/main/java/com/theoriacodex/app/ui/NewPostsEffects.kt`: `NewPostsCheckEffect`, `savedSearchChecker`, `markingNewPostsSeen`, `markNewPostsSeen`, `markViewedNewPost`, `newPostsCollection`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepositoryTest.kt`: `identity ignores display changes and remains source scoped (ADR 0019)`, `following marks the posts it was shown as seen, so only later posts count as new (ADR 0019)`, `restore puts the removed record back in place unless the creator was followed again (ADR 0019)`, `the follow list stops at 200 and an undo into a full list does nothing (ADR 0019)`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModelTest.kt`: `app-open checks wait out the interval even after a failure (ADR 0019)`, `cancelling the automatic check keeps previous durable results and leaves it due (ADR 0019)`, `a refresh of 60 follows records each batch of 25 checks before checking the next (ADR 0019)`, `leaving the app cancels the automatic check but not one the user started (ADR 0019)`, `a manual refresh during the automatic check also checks the follows it skipped (ADR 0019)`, `undoing an unfollow restores the same record in its place (ADR 0019)`
- `app/src/test/java/com/theoriacodex/app/ui/NewPostsCollectionTest.kt`: `only a Viewer opened from Followed or Searches marks the posts it shows seen (ADR 0019)`
- Left to prose: `NewPostsCheckEffect`'s lifecycle timing and `markingNewPostsSeen`'s first-page rule live inside composables with no host test seam (a test would need the app shell faked or a refactor this batch does not make); where marks are kept and that follows stay local are placement rules with nothing to call.
