# 0079. Draft edits keep source-valid terms and tell the user what a change removes

Status: Accepted

## Context
The draft query can span sources whose terms differ. Switching source, adding a post's tag or restoring a saved draft can silently drop terms or reject valid ones.

## Decision
- Typed input aims at a tag type through a prefix such as `artist:` (`typedInputScope`); a prefix the mode cannot search gives null except `tag:`, which always means general tags.
- Unified keeps only terms every source understands. `addIncludeGroup` accepts general tags only so the group suits every source. Post and saved tags come from the source itself so they skip the check typed Gelbooru input needs; a tag searched before on the source counts as confirmed; a typed translation commits the native tag it labels.
- Adding a post's tag moves the draft to that source and `termsLostToPostTerm` reports exactly what is lost; a source's general tags adopt the target group's namespace (`postTermForGroup`).
- `reconcileEnvironment` fits draft and applied search to the sources available now; the typed input stays across source changes, its error until suggestions answer.
- "Animated only" outlives the terms and only the filter reset clears it. Restoring the draft already on screen keeps its validation notice.
- `appliedByMode` remembers the last search per mode; Multi-Search runs are not kept. `SearchTermLimitNotice` explains that Unified sends only the first `budget` terms and a single-source search past it is refused.

## Consequences
Source switching never loses terms silently.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchDraftReducer.kt`: `SearchDraftReducer`, `reconcileEnvironment`, `addIncludeGroup`, `termsLostToPostTerm`, `postTermForGroup`, `addKnownIncludeAlternative`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchScopedInputPolicy.kt`: `typedInputScope`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchTermLimitNotice.kt`: `SearchTermLimitNotice`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchContract.kt`: `appliedByMode`
