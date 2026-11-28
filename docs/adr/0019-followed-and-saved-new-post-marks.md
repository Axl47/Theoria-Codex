# 0019. Follows and new-post marks are local, identity-keyed and rate-limited across restarts

Status: Accepted

## Context

Likes and creator follows are local actions with no remote bookmarks. New-post badges require remembering the last checked first page and what was shown.

## Decision

- A follow is identified by provider identity (`followKey`), never display name. Refreshes cannot resurrect a removed or replaced follow.
- Following marks the supplied visible posts of that creator as both latest and seen, so only later posts count as new; the follow list holds at most `MAX_FOLLOWED_CREATORS` (200).
- Undo of an unfollow restores the record unchanged, including marks, at its old position. It does nothing if the creator was followed again meanwhile (the newer record stays) or the follow list filled up in the meantime.
- Marks (latest ids, seen ids) are kept on the device for followed creators and saved searches; the first check with nothing seen only sets the baseline. Saved-search marks live with UI state because they are device-local and disposable.
- Marking posts seen keeps every seen id that is still in the latest check ahead of older ones before capping the list at `MAX_NEW_POST_MARK_IDS` (`NewPostMarks.seen`), so the cap only ever drops ids no check still lists and a seen post never turns new again, whatever order it was seen in.
- Each follow and each saved search is stamped as attempted when its own request starts (`claimCheck`, `SavedSearchNewPostsChecker.claim`), in the same write that tests it is due, which is one settings write per creator checked. A check that fails, or is cancelled by leaving the app, therefore waits out the check interval, restarts included, and overlapping checks send each request once. Follows and saved searches a cancelled run never started stay due.
- `CreatorFollowsViewModel.refreshIfStale` (the app-open check) refreshes only follows whose own last check or attempt, both saved with the follow, is older than the check interval.
- Results are recorded in batches of `WRITE_BATCH_SIZE` (25) creators per settings write; a batch cut short by leaving the app still records the creators it finished (`NonCancellable`), since their attempts are already stamped.
- `cancelAutomaticRefresh` stops the automatic check when the app leaves the foreground; a refresh the user started carries on. A refresh the user asks for during the automatic check replaces it and covers every follow rather than only the stale ones; the checks the automatic run already recorded stay. An automatic check never replaces a running refresh.
- `unfollowWithUndo` unfollows at once and its Undo restores the same record, so nothing the creator posted meanwhile counts as seen.
- `NewPostsCheckEffect` checks followed creators and saved searches whenever the app comes to the foreground (`repeatOnLifecycle(STARTED)`), once the first frame is drawn and only when the last check is stale (`checkIfStale`, `refreshIfStale`). Leaving the app cancels checks in flight (`cancelAutomaticRefresh`); what they already started is not sent again until the interval passes, so going to Recents and back repeatedly costs no more than one check. Saved searches run read-only (`retry = false`), on the sources and settings their collection uses.
- A live Followed or Searches collection marks a new post seen once its card has been mostly on screen during a visit (`newPostsSeenHandler` with `SeenPostsEffect`, ADR 0152). Posts the visit never scrolled to, posts the filters hid, posts only in pages never loaded, and posts the feed never showed (offline, a failed load) stay new. Any other collection has no new posts.
- The same rule clears marks outside the collections, with nothing marked on load: on a creator's profile and in Search results, a card mostly on screen marks its post seen in Followed and in every saved search that has it new, whichever query showed it (`rememberNewPostsSeen`). On a followed creator's profile every card from the creator's source that is mostly on screen is also recorded as seen for that follow even when no check lists it yet (`markShown`, `NewPostMarks.including`), so a later check never shows a post the profile already showed as new. Opening a profile or a saved search no longer marks anything by itself.
- `markViewedNewPost` marks a post seen in Followed and every saved search once the Viewer shows it, wherever the Viewer was opened; posts the Viewer never reached stay new. Only a Viewer opened from the Followed or Searches collection (`newPostsCollection`) also drops the post from an open collection's markers (`viewedNewPosts`), which keep every other card's outline until the visit ends.

## Consequences

Never add server-side follows or notifications.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepository.kt`: `followKey`, `follow`, `restore`, `claimCheck`, `recordChecks`, `markShown`, `MAX_FOLLOWED_CREATORS`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/NewPostMarks.kt`: `NewPostMarks`, `SavedSearchNewPosts`, `newPostsCheckDue`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `observeSavedSearchNewPosts`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SavedSearchNewPosts.kt`: `SavedSearchNewPostsChecker`, `checkIfStale`, `claim`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModel.kt`: `refreshIfStale`, `cancelAutomaticRefresh`, `startRefresh`, `WRITE_BATCH_SIZE`, `unfollowWithUndo`
- `app/src/main/java/com/theoriacodex/app/ui/NewPostsEffects.kt`: `NewPostsCheckEffect`, `savedSearchChecker`, `newPostsSeenHandler`, `markNewPostsSeen`, `markViewedNewPost`, `newPostsCollection`, `NewPostsSeen`, `rememberNewPostsSeen`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/CreatorFollowsRepositoryTest.kt`: `identity ignores display changes and remains source scoped (ADR 0019)`, `following marks the posts it was shown as seen, so only later posts count as new (ADR 0019)`, `a post seen on the profile before a check lists it never counts as new (ADR 0019)`, `restore puts the removed record back in place unless the creator was followed again (ADR 0019)`, `the follow list stops at 200 and an undo into a full list does nothing (ADR 0019)`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorFollowsViewModelTest.kt`: `app-open checks wait out the interval even after a failure (ADR 0019)`, `an automatic check cancelled by leaving the app keeps durable results and is not sent again within the interval (ADR 0019)`, `a refresh of 60 follows records each batch of 25 checks before checking the next (ADR 0019)`, `leaving the app cancels the automatic check but not one the user started (ADR 0019)`, `a manual refresh during the automatic check also checks the follows it skipped (ADR 0019)`, `undoing an unfollow restores the same record in its place (ADR 0019)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/NewPostMarksTest.kt`: `seen posts still in the latest check stay seen however far back they were seen (ADR 0019)`
- `app/src/test/java/com/theoriacodex/app/creator/CreatorProfileViewModelTest.kt`: `opening a followed creator snapshots its seen ids and marks nothing seen (ADR 0019)`
- `feature-discover/src/test/kotlin/com/theoriacodex/app/creator/CreatorProfileSeenPostsTest.kt`: `a followed creator's profile reports only the new posts it showed (ADR 0019)`
- `feature-search/src/test/kotlin/com/theoriacodex/app/search/SearchSeenPostsTest.kt`: `search results report only the new posts they showed (ADR 0019)`
- `app/src/test/java/com/theoriacodex/app/ui/NewPostsCollectionTest.kt`: `only a Viewer opened from Followed or Searches hides the posts it shows from an open collection's markers (ADR 0019)`
- `app/src/test/java/com/theoriacodex/app/ui/NewPostMarkersTest.kt`: `a visit clears only the new posts it showed mostly on screen, keeps every marker until it ends, and the Viewer clears what it opens wherever it was opened (ADR 0019, 0152)`, `a Viewer opened from Search clears the marks of the posts it shows without touching collection markers (ADR 0019)`
- Left to prose: `NewPostsCheckEffect`'s lifecycle timing lives inside a composable with no host test seam (a test would need the app shell faked), so it is unguarded; where marks are kept and that follows stay local are placement rules with nothing to call.
