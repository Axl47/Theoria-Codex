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

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchScopedInputPolicyTest.kt`: `all scoped prefixes preserve facet namespace aliases and polarity (ADR 0079)`, `namespace aliases resolve exactly while general aliases may fall back by facet (ADR 0079)`, `draft commit uses canonical unified unsupported and removal messages (ADR 0079)`, `a prefix the source cannot search gives no scope, except tag, which means general tags (ADR 0079)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchDraftReducerTest.kt`: `unified input blocks source taxonomy and historical restore removes it (ADR 0079)`, `include alternatives stay in one OR group and can become required (ADR 0079)`, `a group added at once keeps only general tags, so it suits every source (ADR 0079)`, `post tags join a group of their type, even on sources that validate typed input (ADR 0079)`, `an autocomplete result with only a searched Gelbooru tag can commit its input (ADR 0079)`, `typed pixiv translations commit the native tag they label (ADR 0079)`, `only a typed tag from another source reports what the switch would drop (ADR 0079)`, `post taxonomy changes source without losing portable filters and removal is exact (ADR 0079)`, `unavailable source-owned post term leaves draft unchanged while portable post tag stays unified (ADR 0079)`, `a source that leaves takes its terms from the draft and the applied search but not the typed input (ADR 0079)`, `clearing the draft keeps the current mode and media (ADR 0079)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchStateContractTest.kt`: `restoring keeps the notice that stored source terms were removed (ADR 0079)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/state/SearchRequestReducerTest.kt`: `each mode remembers its last search and Multi-Search does not (ADR 0079)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/SearchTermLimitNoticeTest.kt`: `unified warns about a capped source while a single-source search is refused (ADR 0079)`, `queries within the budget and terms Unified never sends produce no notice (ADR 0079, 0080)`, `a ranked sort lowers the budget and says so (ADR 0079)`
- Left to prose: that only the filter reset clears "Animated only" depends on the Search screen's toggle in `feature-search`, which is not guarded.
