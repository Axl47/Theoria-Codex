# 0139. Search runs live after a pause, and a search is remembered only once the user settles it

Status: Accepted

## Context

Search has no Search button: results follow the query as tags, sources and filters change. Running a request per keystroke or per edit would waste provider calls, and remembering every intermediate search would fill history and Recents with queries the user never meant to keep. The route also starts before its saved state has been restored, and edits can arrive while another screen covers Search.

## Decision

- `LiveSearchScheduler` runs the draft after a pause following each committed edit (a tag added, removed or excluded, a source or filter changed). Typing alone never schedules a run. A newer edit restarts the wait, and the caller cancels the request in flight when it schedules. The wait is `singleSourceMs` (600 ms) for a single source and `multiSourceMs` (900 ms) when one search asks several sources. `cancel` returns true when a run was waiting so the caller can run it at once.
- `SearchViewModel.scheduleLiveSearch` cancels a root request that no longer asks for exactly the draft, and schedules a run only when the draft has pending changes and is not already running. An empty draft is runnable like any other. Before any search has run the applied search is only a placeholder, so a draft equal to it still needs its own run once the first search it superseded is cancelled, including by an earlier edit whose replacement is still waiting (`firstSearchSuperseded`); otherwise nothing would answer.
- Picking a source (`selectMode`, `toggleTemporarySource`) reschedules only once a search is on screen, waiting or running (`hasCommittedSearch`); before that it only prepares the draft. A Source mix change (`scheduleMixedSearch`) restarts the same wait and drops the root request in flight, so a slider drag ends in one search; before any search has run it only prepares the next one.
- `ApplyDraft` only settles the search when live search already runs or shows exactly the draft (`showsDraft`). `SettleSearch` runs the waiting draft at once when one was waiting, otherwise settles.
- A draft edit made over Search is staged (`stageDraftEdit`): it cancels the waiting run and edits the draft without running it behind the other screen. `Resume` then schedules it, through `runAfterRestoration` when the edit was staged before Search first opened so it runs on the restored draft, not the blank one.
- A search requested from outside Search (Recents, a Codex, a tag from elsewhere) waits for restoration (latest request wins), because restoration would replace its draft and request. The route delivers queued actions, then the first `Resume`, then `Restore`.
- `SearchSettlement` decides when a live search settles: the composer or the filter sheet closes on it (filters apply live as they change), a post is opened from it, or its results are first scrolled after the search finished. Settling moves it into the route's history and records it in Recents. A search replaced before it settles leaves no trace. A search asked for outright (a saved search, Recents, a tag from elsewhere) settles as soon as its results show; a step through history was settled and recorded before, so it is neither settled nor recorded again.
- The running search's partial results are kept only for recording: if the search settled on partial results and is cancelled before its final answer, `superseded` records them because they are what the user took. Recording runs under `NonCancellable`, so it finishes even if the user leaves Search.
- A typed number that would be the draft's only tag is an nhentai gallery ID: it opens the Viewer (`directNhentaiGalleryEffect`, reusing a result already on screen) and the draft stays as it was; a number not yet added is added first by `applyDraft`.
- `SearchViewModel` is the one boundary that schedules route work, publishes render state, persists compact reconstruction input and emits one-shot effects; the coordinator stays the provider/domain engine and the application container may create but not retain it. `SearchSavedQueryCodec` stores one compact string in `SavedStateHandle`; a draft saved before the media part existed has 7 parts and decodes with media Any.
- `SearchChromeState` keeps the composer and the tucked-away search bar across recreation and the Viewer round trip, so returning does not push the results down; the filter and side sheets do not survive.
- The source-mix write comes back through `SearchViewModel.synchronizeEnvironment`, which reruns the search on screen under the live-query pause.

## Consequences

Edits are cheap and results stay current without a button, at the cost of a delay before every run and several flags (`draftStaged`, `firstSearchSuperseded`, the settlement state) that must be reasoned about together. Changing the pause or what counts as settling changes what Recents and history contain.

## Applies to

- `feature-search/src/main/kotlin/com/theoriacodex/app/search/LiveSearchScheduler.kt`: `LiveSearchDelays`, `LiveSearchScheduler`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchSettlement.kt`: `SearchSettlement`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchViewModel.kt`: `scheduleLiveSearch`, `scheduleMixedSearch`, `selectMode`, `stageDraftEdit`, `runAfterRestoration`, `applyDraft`, `commitTagInput`, `SearchAcceptance`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchDirectGallery.kt`: `directNhentaiGalleryEffect`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchSavedState.kt`: `SearchSavedQueryCodec`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchScreen.kt`: `SearchChromeState`
- `feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchSourceMix.kt`: `SearchSourceMix`
