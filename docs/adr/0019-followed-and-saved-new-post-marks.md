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

## Consequences

Never add server-side follows or notifications.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepository.kt`: `followKey`, `follow`, `restore`, `recordChecks`, `MAX_FOLLOWED_CREATORS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/NewPostMarks.kt`: `NewPostMarks`, `SavedSearchNewPosts`, `newPostsCheckDue`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `observeSavedSearchNewPosts`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModel.kt`: `refreshIfStale`, `cancelAutomaticRefresh`, `startRefresh`, `WRITE_BATCH_SIZE`, `unfollowWithUndo`
