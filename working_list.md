# Working List

## Current Task: Optimize Search Tag Suggestions And Pixiv Discovery

### In Progress

- None.

### Pending

- None.

### Done

- [x] Re-read the task-orchestrator workflow and the complete HTML ExecPlan standard.
- [x] Confirmed the pre-existing dirty files are confined to unrelated OCR/settings work; this task will not edit them.
- [x] Finalized the local-first/native-value architecture and created `.docs/exec/search-tag-suggestion-performance.html`; required sections are present and the host HTML parser exited successfully (its legacy parser reported expected HTML5 element warnings).
- [x] Added alternate-label semantics, pure provider-aware ranking, full-prefix cache reads, learned-row restart priority, and explicit active-trending origins. Verification: focused `TagSuggestionRankingTest` and `TagSuggestionStoreTest` passed; the first compile exposed and then verified the public origin-contract correction.
- [x] Added immediate cached publication, four-second provider bounds, fifteen-minute exact-prefix reuse, fair concurrent Unified fan-out, and interactive cancellation of background trending. Verification: focused `SearchCoordinatorTest` and `RequestOwnershipSearchViewModelTest` pass, including virtual-time concurrency, timeout, reuse, and pre-debounce publication cases.
- [x] Added Pixiv native/translated tag parsing, locale request headers, stable provider-order ranking, and native-safe clean UI labels. Verification: focused `PixivSourceAdapterTest`, `TagSuggestionRankingTest`, and `SearchFacetUiTest` pass.
- [x] Separated active trending membership from autocomplete/seed/seen/count origins, changed trending replacement to preserve other knowledge, and retained the general lexicon as For You's offline fallback. Verification: focused store, Search coordinator, and app compilation tests pass.
- [x] Extracted `SearchSuggestionCoordinator` and pure suggestion-state transitions after the hotspot gate identified growth in frozen owners; split the new test coverage and removed SearchCoordinator's now-stale hotspot exception. Verification: SearchCoordinator is 787 lines, SearchViewModel is 1099/1100, tests are under 700 lines, and the hotspot gate passes.
- [x] Synchronized the additive tag-cache fields with the central Gson/R8 contract after its guard identified the stale manifest and fixture.
- [x] Completed the integrated host-only validation batch. Verification: relevant core/app module tests, 555 app tests with three expected opt-in skips, Debug compilation, app/app-logic/core Detekt, aggregate Kover XML, and Kover verification all passed.
- [x] Updated `AGENTS.md` and the living ExecPlan with the local-first, origin-separated, native-Pixiv suggestion invariants.

### Needs Human Validation

- [!] In an isolated `com.theoriacodex.debug` install, confirm immediate Pixiv cached suggestions, translated/native row presentation, repeated-prefix reuse, refreshed Unified results, and genuine Trending rows. No APK was installed or launched during host validation.
