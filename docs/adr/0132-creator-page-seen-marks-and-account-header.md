# 0132. The creator page marks new posts from a seen-ids snapshot taken before the visit, and never waits on account details

Status: Accepted

## Context

A followed creator's page shows which uploads are new since the last visit. Opening the page is itself a visit that records posts as seen, so the page must read what was seen first. Account sites report facts that are partial or misleading (private favorites count as 0), and a details request must not hold up the posts. The page also hosts the Follow button and its Undo, which can be drawn in two places.

## Decision

- `CreatorProfileViewModel.snapshotSeen` reads the follow's seen ids when the page opens (`CreatorSeenSnapshot`, null when the creator is not followed), before this page's first accepted visit adds to them. Only a fresh uploads list is a visit (`recordAcceptedVisit`: `REFRESH` of `AccountFeed.UPLOADS`); it waits for the snapshot read to finish, and its bookkeeping outlives the completed request but not the route owner.
- `newSinceVisitBoundary` puts the "Already seen" divider before the first loaded post that was seen, when unseen posts come before it. It is null when no loaded post was seen (the rest are unloaded, not new) or the first post was seen. It applies only to the uploads feed.
- `creatorTopTags` counts general tags only: a tag on a single post says nothing about the creator, so only tags on at least two posts count; the creator's own tag (uploads query, profile id or display name, compared with spaces and underscores equal and ignoring case) never does; tags a source types as artist, meta or any other kind are left out, untyped tags count as general.
- `accountHeaderText` leaves out a favorites count of 0, because sites report 0 for private favorites, which would read as "no favorites". A failed or empty details request leaves the header bare and the page never waits on it (`launchDetails`). `canFollow` is false without an uploads query because following checks the uploads feed.
- `CreatorProfileCoordinator.load` serves an account's feeds from its account adapter, and any other creator as an uploads search; both are bounded by `SOURCE_TIMEOUT_MS`, run in `providerContext` (`Dispatchers.Default`, so pages are fetched and parsed off the main thread), and teach the file mirror index. Post resolution is shared with the Viewer (`PostResolutionFlights`) so a card and the Viewer asking for one post at once fetch it once.
- `onSourceAvailabilityChanged` invalidates the active request only when this creator's capability is lost. After a reconnect, `retryAfterReconnect` waits for a request begun offline to settle, since it may still be failing, so its outcome gets the retry too.
- Each feed keeps its own scroll position and duration reveal order; the uploads feed keeps the retain key (`creator`) it had before accounts had other feeds, other feeds use `creator-<name>`. Pull to refresh is how paging resumes after a dismissed page failure. The filter sheet's reset on this page has no Undo, so the returned undo callback is deliberately discarded.
- An unfollow's Undo is drawn where the unfollow was made (`CreatorFollowAnchors`): in the page's Follow button, which reads as one "Unfollowed <name>" announcement with Undo as its action, or in the followed-creators sheet's vacated row. The sheet holds a row only for an unfollow made there, keeps its place (`followedCreatorRows`, creators with new posts first otherwise follow order) until it is restored or its creator is followed again, and its check button spins for at least `CHECK_MIN_SPIN_MS` so a check that finds nothing new is still seen to run.

## Consequences

The divider is stable during a visit even though marks update at once, and a slow or failing details endpoint costs only the header facts. Recording seen posts earlier than the snapshot read would erase the divider.

## Applies to

- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorProfileViewModel.kt`: `CreatorProfileViewModel`, `snapshotSeen`, `recordAcceptedVisit`, `launchDetails`, `onSourceAvailabilityChanged`, `retryAfterReconnect`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorNewSinceVisit.kt`: `CreatorSeenSnapshot`, `newSinceVisitBoundary`, `rememberNewSinceVisit`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorTagChips.kt`: `creatorTopTags`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/AccountHeader.kt`: `accountHeaderText`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowButton.kt`: `canFollow`, `CreatorFollowButton`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorFollowAnchors.kt`: `CreatorFollowAnchors`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorProfileCoordinator.kt`: `CreatorRouteEngine`, `CreatorProfileCoordinator`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/CreatorProfileScreen.kt`: `retainKey`, `CreatorProfileScreen`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/FollowedCreatorRows.kt`: `followedCreatorRows`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/creator/FollowedCreatorsSheet.kt`: `FollowedCreatorsSheet`, `CheckButton`
