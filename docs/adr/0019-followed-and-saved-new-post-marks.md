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

## Consequences

Never add server-side follows or notifications.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepository.kt`: `followKey`, `follow`, `restore`, `recordChecks`, `MAX_FOLLOWED_CREATORS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/NewPostMarks.kt`: `NewPostMarks`, `SavedSearchNewPosts`, `newPostsCheckDue`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `observeSavedSearchNewPosts`
