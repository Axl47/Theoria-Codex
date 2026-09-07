# App testing remediation

- [x] F01 shared fixture container at real app-shell boundary, real Room/DataStore/services/owners and debug Activity recreation host written.
- [x] F01 grouped Search group-editor/apply/page/Viewer return/tab return/Activity recreation journey written; exact Multi-Search and cold FYP Recents dispatch written.
- [x] F01 collection create/overflow-rename/bulk-edit/remove/snackbar-Undo/long-press/delete-Cancel journey written.
- [x] F01 FYP real post Like → related shelf, stable accepted-history/root-count, shared Save-to-Codex action/statistic, Creator navigation/back written.
- [x] F02 real profile mutations: create/switch/delete isolation, cancel, last profile, missing profile, failed cleanup/retry and cancellation tests written.
- [x] F06 Recents and bulk Codex intermediate deletion, exact Undo, clear/restore Retry assertions written.
- [x] F07 helper test names describe their actual unit boundary.
- [~] Root owns compile/host/device validation; no result claimed yet. Address coordinated failures when received.

## Boundaries and evidence

`JourneyAppContainer` exists only in debug/benchmarkRelease fixture sources. It uses caller-owned temporary storage, real Room/DataStore/query persistence, real coordinators/workflows, and controlled external SourceAdapter data. Its default HTTP implementation fails before opening a socket. OCR downloads/translations and APK installation fail locally if unexpectedly attempted. `TheoriaAppContent` is the existing real app composition/navigation boundary; tests do not replace callbacks, screens, or route owners. `JourneyTestActivity` is debug-only and is recreated with Android ActivityScenario.

Runtime replacements for removed behavior-shaped guards:
- collapsed applied Search summary: SearchNavigationJourneyTest asserts the actual rendered `Pixiv · landscape AND (blue OR green)` placeholder after UI Apply.
- owner/navigational wiring and readiness: same test drives actual group editor, provider execution, pagination, Viewer return, tab switching and Activity recreation, observes preserved card position and exactly one accepted search/watched membership; second method opens actual Recents Multi-Search and FYP rows and checks precise requests.
- FYP shared actions/related shelf: ForYouPostActionsJourneyTest taps actual Like then checks Room Likes/system Codex, visible More like this shelf, unchanged history/root requests, real long-press Save-to-Codex membership/statistic and Creator navigation/back.
- actionable feedback/collection control wiring: CodexCollectionJourneyTest observes real Room removal before clicking the visible Undo snackbar and verifies exact restored memberships; create/rename/long-press deletion cancellation exercise actual controls.
- statistics source guards: existing outcome tests retained; new FYP save journey checks durable membership and one statistic through shell callbacks. No new claim of full statistics UI/performance coverage.

## Production finding repaired

Settings profile cleanup previously let storage exceptions escape an owner coroutine. `removeRequestedProfile` now catches ordinary failures while preserving cancellation and emits a retryable user-facing message; settings membership remains until cleanup completes. Default cleanup remains idempotent, and its retry is exercised after a real Codex deletion failure. No claim of all-or-nothing transaction across multiple profile repositories is made.

## Tests for root validation

JVM: `SettingsProfileWorkflowTest`, `SettingsViewModelTest`, `RecentsClearWorkflowTest`, `CodexRemovalWorkflowTest`, `AvailableSaveTargetCodicesTest`, `CreatorProfileActionPresentationTest`, `DestinationStateBoundarySubscriptionTest`.

Device: `com.theoriacodex.app.journeys.SearchNavigationJourneyTest`, `com.theoriacodex.app.journeys.CodexCollectionJourneyTest`, `com.theoriacodex.app.journeys.ForYouPostActionsJourneyTest`.

No Gradle, device, network, or production/package data operations were run by this lane. Root is coordinating packaged identity/signing proof and execution. Activity recreation is tested; process death is not claimed.

## First coordinated compile / host pass

Root's combined compile succeeded for shared fixture, debug host, Android journeys, and unit sources. Initial host failures exposed fixture assumptions: default settings has Main and Alt profiles (single-profile scenarios now explicitly seed one); newly added Retry tests used tied wall-clock timestamps and in-memory raw membership insertion order. Recents now uses a monotonic clock. Codex seeds explicit save times, checks exact restored row values and explicit newest-saved presentation order, preserving intermediate deletion assertions. Root will rerun the affected classes. New journey uses Compose v2 rule. No device results yet.

## Cold graph relaunch coverage

Added `SearchNavigationJourneyTest.coldGraphRelaunchRestoresAppliedSourceQueryAndCanonicalScrollFromDisk`: enter/apply a Pixiv query through the screen, scroll to canonical index 6, observe durable scroll, fully close the Activity, shut down/join fixture store owners and close Room, then create a new container and Activity over the same directory. Assert different database/settings objects, exact reissued source/query, actual applied summary and matching visible card geometry, and retained persisted scroll/query. The reusable fixture helper runs graph creation/shutdown on instrumentation-owned IO. This proves disk restoration with fresh owners; it is not OS process-kill proof or deep-page restoration coverage.

The original Search/Viewer journey additionally recreates the Activity while Viewer is displaying the opened image, then recreates Search after tab round-trip. It asserts lifetime `searchCount == 1` and `watchedPostCount == 1` alongside Recents memberships, so deduplicated history cannot mask double-counted lifetime events. Root validation pending for these additions.

## First physical journey batch

Root reports passing Codex collection controls/Undo and Recents Multi-Search/FYP replay on SM-S926U (Android 16). Grouped/cold Search setup selected Pixiv after adding the first draft tag; source selection intentionally restores the selected mode's saved draft, so the setup now chooses source first and then enters tags. Exact applied-summary and group assertions remain. Grid scroll finders now use the unmerged semantics tree, where the real grid owns ScrollToIndex. The FYP post-action seed now uses the production-required Unified Query even with one explicit participating source. Debug host holds FLAG_KEEP_SCREEN_ON only on its visible window. Root targeted rerun pending; no blanket pass claimed.
