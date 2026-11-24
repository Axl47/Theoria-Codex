# 0019. Follows and new-post marks are local, identity-keyed and rate-limited across restarts

Status: Accepted

## Context

Likes and creator follows are local actions with no remote bookmarks. New-post badges require remembering the last checked first page and what was shown.

## Decision

- A follow is identified by provider identity (`followKey`), never display name. Refreshes cannot resurrect a removed or replaced follow.
- Following marks the supplied visible posts of that creator as both latest and seen, so only later posts count as new; the follow list holds at most `MAX_FOLLOWED_CREATORS` (200).
- Undo of an unfollow restores the record unchanged, including marks, at its old position. It does nothing if the creator was followed again meanwhile (the newer record stays) or the follow list filled up in the meantime.
- Marks (latest ids, seen ids) are kept on the device for followed creators and saved searches; the first check with nothing seen only sets the baseline. Saved-search marks live with UI state because they are device-local and disposable.
- Each follow and each saved search is stamped as attempted when its check starts (`claimChecks`, `SavedSearchNewPostsChecker.claim`), in the same write that tests it is due. A check that fails, or is cancelled by leaving the app, therefore waits out the check interval, restarts included, and overlapping checks send each request once. Follows and saved searches a cancelled run never started stay due.
- `CreatorFollowsViewModel.refreshIfStale` (the app-open check) refreshes only follows whose own last check or attempt, both saved with the follow, is older than the check interval.
- Checks are recorded in batches of `WRITE_BATCH_SIZE` (25) creators per settings write, so a large list neither rewrites settings per creator nor loses its progress when cancelled.
- `cancelAutomaticRefresh` stops the automatic check when the app leaves the foreground; a refresh the user started carries on. A refresh the user asks for during the automatic check replaces it and covers every follow rather than only the stale ones; the checks the automatic run already recorded stay. An automatic check never replaces a running refresh.
- `unfollowWithUndo` unfollows at once and its Undo restores the same record, so nothing the creator posted meanwhile counts as seen.
- `NewPostsCheckEffect` checks followed creators and saved searches whenever the app comes to the foreground (`repeatOnLifecycle(STARTED)`), once the first frame is drawn and only when the last check is stale (`checkIfStale`, `refreshIfStale`). Leaving the app cancels checks in flight (`cancelAutomaticRefresh`); what they already started is not sent again until the interval passes, so going to Recents and back repeatedly costs no more than one check. Saved searches run read-only (`retry = false`), on the sources and settings their collection uses.
- `markingNewPostsSeen` marks the posts a live Followed or Searches collection shows (after the grid's display filters) as seen once its feed has drawn its first page, once per opening of the collection: loading a later page flips `rendered` again but must not mark its posts or late checks seen. A check that lands later, posts the filters hid, posts only in later pages, and posts the feed never showed (offline, a failed load) stay new. Any other collection has no new posts.
- `markViewedNewPost` marks a post seen once the Viewer shows it from the Followed or Searches collection, whether opened from a Saved shelf or the full collection (`newPostsCollection`); posts the Viewer never reached stay new, and other sessions have no new posts.

## Consequences

Never add server-side follows or notifications.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepository.kt`: `followKey`, `follow`, `restore`, `claimChecks`, `recordChecks`, `MAX_FOLLOWED_CREATORS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/NewPostMarks.kt`: `NewPostMarks`, `SavedSearchNewPosts`, `newPostsCheckDue`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `observeSavedSearchNewPosts`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchNewPosts.kt`: `SavedSearchNewPostsChecker`, `checkIfStale`, `claim`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModel.kt`: `refreshIfStale`, `cancelAutomaticRefresh`, `startRefresh`, `WRITE_BATCH_SIZE`, `unfollowWithUndo`
- `app/src/main/java/com/theoriacodex/app/ui/NewPostsEffects.kt`: `NewPostsCheckEffect`, `savedSearchChecker`, `markingNewPostsSeen`, `markNewPostsSeen`, `markViewedNewPost`, `newPostsCollection`
