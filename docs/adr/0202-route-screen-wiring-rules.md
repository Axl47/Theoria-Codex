# 0202. Route screens pass the shell only what each callback reads and publish owners before the lifecycle resume that uses them

Status: Accepted

## Context

Search, For You and the Saved shelves are composed lazily inside navigation entries. Their owners are created and published at different moments, filter and sort changes arrive as separate events, and keystrokes or paging must not recompose result cards.

## Decision

- `SearchRoute` publishes its owner to the shell (`onOwnerAvailable`) before the resume observer synchronizes, so actions queued before Search had an owner, such as Viewer tag edits staged from Library, reach it before the resume that runs them. Effects run before `SideEffect`, which is why the publication is in the effect itself.
- `SearchRoute` captures only what each callback reads (`appliedQueryHash` for the post resolver), so keystrokes and paging leave result cards untouched. Whether the composer is open is held in the route so a typed gallery ID can close it before the Viewer opens over it. The omnibox entries composable is composed only while the composer is open, so its stores are not observed behind the results.
- `ForYouRoute`'s restore state keeps the source, sort, media filter and deck-or-grid choice; the owner itself keeps nothing, so a recreated owner deals a fresh Mix from them. The owner is handed what to deal from only while For You is on screen or the tabs are heading to it (`ForYouRouteConfig.shown`), so a tab change that merely passes over For You asks the sources for nothing. Resetting filters is one restore change (`sortMode = null`, `animatedOnly = false`), since two changes would each start from the state before the other.
- `SavedCodexRoute` leads each Codex shelf's strip with its new posts. Followed and saved-search sections are live feeds, so only their card hearts like; the hold menu, like Codex detail's, can also unlike from Likes. The duration owner is created above the shelf boundary so its known lengths reach the length sorts.

## Consequences

Adding state to these routes means deciding whether it belongs in the restore state, which survives the owner, or in the owner, which does not.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/routes/SearchRoute.kt`: `SearchRoute`, `SearchRouteConfig`, `SearchRouteResumeObserver`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ForYouRoute.kt`: `ForYouRoute`, `ForYouRouteConfig`, `FollowForYouEnvironment`, `deckCallbacks`
- `app/src/main/java/com/theoriacodex/app/ui/routes/SavedCodexRoute.kt`: `SavedCodexRoute`
