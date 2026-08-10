---
created_at: 2026-05-31T00:13:56Z
updated_at: 2026-08-03T00:03:12-04:00
---
# Working List

## Current Task: Add FYP Recommendation History to Recents

### In Progress

None.

### Pending

- [ ] Manually confirm FYP search rows, full tag wrapping, and filter-row scrolling in the isolated Debug app.

### Done

- [x] Persist and replay the exact source-by-source FYP seed through the For You route owner.
  - Evidence: the versioned recent-search payload now carries source-specific seed tags; `ReplaySearch` supersedes current For You work and executes those exact per-source queries and sort through `ForYouCoordinator`.
- [x] Make FYP rows navigate to For You and reorder Searches before FYP.
  - Evidence: both the FYP section and All dispatch through a queued navigation-owner action before jumping directly to For You; Recents filter order is Watched, Codex, Searches, FYP, All.
- [x] Add focused persistence, coordinator, reducer, and navigation regression coverage.
  - Evidence: payload/Room round trips, exact coordinator replay, typed reducer handoff, compatibility fallback, direct navigation ownership, and tab order all pass their focused host tests.
- [x] Run one bounded host validation batch and update the ExecPlan evidence.
  - Evidence: 91 focused tests ran with zero failures/errors and seven intentional file-backed skips; app compilation, app/Room Detekt, HTML validation, and diff checks passed. No device, connected, install, package mutation, or live-provider lane ran.
- [x] Correct the feature boundary to FYP searches rather than result posts.
  - Evidence: `ForYouCoordinator` owns the accepted root generation and exact seed/source context. It now records one typed recent search after root acceptance; pagination and result posts do not write FYP history, while direct Viewer activity continues through Watched.
- [x] Define the implementation boundary before runtime edits.
  - Evidence: `.docs/exec/fyp-recents-history.html` fixes generation/page recording, membership identity, FYP/All UI behavior, clear/Undo, failure isolation, and bounded validation.
- [x] Add FYP search projection, All inclusion, and independent clear/Undo.
  - Evidence: normal Searches exclude FYP, the FYP filter renders the generated search rows, All receives them through the existing activity merge, and prefix-scoped clearing preserves normal search history.
- [x] Remove title truncation from recent-search rows.
  - Evidence: tag titles retain comma-separated presentation and no longer set a single-line maximum or ellipsis overflow.
- [x] Complete the corrected bounded host validation batch.
  - Evidence: 68 focused tests ran with zero failures/errors and seven intentional file-backed skips; Debug compilation, app/Room Detekt, HTML validation, and diff checks passed. The broader core-data Detekt lane remains red only on five pre-existing unrelated complexity/length findings. No device, connected, install, package mutation, or live-provider lane ran.

## Current Task: Make Recent Searches Source-Aware

### In Progress

None.

### Pending

- [ ] Manually confirm Recent Searches spacing and source-logo rendering in the isolated Debug app.

### Done

- [x] Trace Search execution, Recents persistence, historical reopening, and source-logo ownership.
  - Evidence: `SearchCoordinator` records accepted searches but deliberately skips temporary scopes; `RecentSearchEntry` and Room store only a `Query`; `ApplyHistoricalQuery` restores only `QueryMode`; and `SourceLogo` is the shared post-metadata logo renderer. The smallest complete repair is a backward-compatible source-aware recent-search payload plus explicit historical source-scope handoff.
- [x] Define the implementation boundary before runtime edits.
  - Evidence: `.docs/exec/source-aware-recent-searches.html` fixes the data flow, legacy fallback, UI states, historical reopen behavior, error handling, and bounded validation lane.
- [x] Implement durable source-aware search history and the revised Recents row.
  - Evidence: accepted source, Unified, and temporary executions now record their search kind and participating sources; temporary executions remain excluded from applied-query/scroll persistence. Room uses a backward-compatible wrapper in the existing payload column. Recents uses `SourceLogo` for source searches, places relative time under the leading icon, renders comma-separated tags as the title, omits sort, and limits source context to Unified/Multi-Search rows.
- [x] Restore historical Multi-Search scope through the Search owner.
  - Evidence: a Recent Multi-Search now reopens with its explicit source set; unavailable sets fail with a targeted message instead of silently becoming global Unified.
- [x] Add focused persistence, restoration, and presentation regression coverage.
  - Evidence: 39 focused tests passed across the payload codec, Room repository, Recents presentation, Search coordinator, and Search ViewModel with zero failures/errors/skips.
- [x] Run one bounded validation batch and close deterministic evidence.
  - Evidence: focused tests, `:app:compileDebugKotlin`, `:app:detektDebug`, and `:core-data-android:detektDebug` passed; `git diff --check` passed. Broad `:core-data:detekt` remains red only on five inherited findings in untouched migration/policy and legacy test owners. No device, connected, install, package mutation, or live-provider lane ran.

## Current Task: Prepare v0.8.0 Release

### In Progress

None.

### Pending

- [!] API 35 connected instrumentation is unavailable locally and remains for GitHub Actions.

### Done

- [x] Prepare the approved v0.8.0 release unit.
  - Evidence: `app/build.gradle.kts` declares `0.8.0` / `1500000800`; `release-notes/v0.8.0.md` contains the approved user-facing notes.
- [x] Prove device-test package isolation without installing an APK.
  - Evidence: the connected-test dry run includes `verifyDebugInstallableApplicationId`; a host-built Debug APK passed that verifier and reports `com.theoriacodex.debug`.
- [x] Repair release-gate drift exposed by focused validation.
  - Evidence: the Codex delete dialog moved behind a behavior-preserving composable boundary so app Detekt passes; the stable-key architecture guard now checks the shared masonry-grid owner, and all 433 app unit tests pass with three skips.
- [x] Build and verify the optimized release artifact.
  - Evidence: `assembleRelease`, release and release-acceptance JSON/R8 contracts, and 36 workflow/helper tests pass; release metadata reports `0.8.0` / `1500000800`.
- [x] Restore exact hotspot budgets for the five owners grown by post-audit UX work.
  - Evidence: release changelog UI, Search persistence, Search empty-state policy, Codex dialogs, and UI-restore records now have focused owners; all five inherited files are below their frozen ceilings, CodexList is below the default 900-line limit, exact hotspot debt passes, and production duplication is 0.59% under the 0.60% gate. App/core-data compilation, Detekt, and 560 unit tests pass with three unchanged skips.
- [x] Rerun the complete host quality and release validation batch.
  - Evidence: all seven Detekt owners, aggregate Kover XML/verification, hotspot and duplication gates, 36 helper/workflow tests, app/core-data unit suites, final `assembleRelease`, and both release JSON/R8 verifiers pass. Final release metadata reports `com.theoriacodex`, `0.8.0`, and `1500000800`. API 35 connected instrumentation remains explicitly unrun for GitHub Actions.
- [x] Present and receive approval for the final release diff.
  - Evidence: the user approved the final v0.8.0 diff, exact `chore(release): prepare v0.8.0` commit message, and future `v0.8.0` tag on 2026-08-08.

## Current Task: Reuse the Collapsed Search Field for Applied Context

### In Progress

None.

### Pending

- [ ] Manually confirm collapsed summary, focus transition, and ellipsis in the isolated Debug app.

### Done

- [x] Run one bounded validation batch and close deterministic UX-005 evidence.
  - Evidence: 3 `CollapsedSearchContextTest` and 1 `CollapsedSearchFieldArchitectureTest` cases passed with zero failures/errors; `:app:compileDebugKotlin` and `:app:detektDebug` passed. No connected, device, package mutation, or live-provider command ran.
- [x] Add focused summary and Search ownership regression coverage.
  - Evidence: formatter tests cover single, Unified, temporary, excluded-only, and filtered context; the architecture guard locks applied-state ownership, placeholder rendering, focus behavior, and the no-new-row constraint.
- [x] Implement the compact applied-context presentation in the existing field.
  - Evidence: the unfocused empty field now shows source, first useful applied term, and filter count; the real input remains unchanged, focus removes the placeholder, and long summaries ellipsize within the current single-line field.
- [x] Trace Search focus, applied-query, source-scope, and filter-state ownership.
  - Evidence: `SearchScreen` already owns focus and route-local visibility filters; `SearchQueryUiState` separately owns authoritative applied query/source scope. The existing unfocused placeholder slot can render applied context without changing field height, input state, persistence, or the pending-draft flow.
- [x] Define the UX-005 implementation boundary before runtime edits.
  - Evidence: `.docs/exec/reuse-collapsed-search-field-for-applied-context.html` defines source/query/filter priority, focus transitions, ellipsis, and the no-new-row constraint.

## Current Task: Standardize Secondary Chrome and Feed Filtering

### In Progress

None.

### Pending

- [ ] Manually confirm secondary-bar geometry and filter-sheet/FAB states in the isolated Debug app.

### Done

- [x] Add focused architecture coverage and run one bounded validation batch.
  - Evidence: 2 `NavigationChromeArchitectureTest` cases passed with zero failures/errors; `:app:compileDebugKotlin` and `:app:detektDebug` passed, with Detekt retaining its known 10 analyzer-resolution diagnostics. No connected, device, package mutation, or live-provider command ran.
- [x] Adopt the shared filter sheet and active-state affordance in Search, For You, and Creator Profile.
  - Evidence: all three routes retain their existing filter values and callbacks but render through `FeedFilterSheet`; `FeedFilterFab` uses route-owned non-default state for tint and accessibility state without adding grid-adjacent summary content.
- [x] Adopt the shared app bar in Codex detail, Creator Profile, and Viewer.
  - Evidence: all three routes render through `SecondaryScreenAppBar`; Viewer retains its translucent overlay and moves Like into the right action region, while Codex selection and Creator sharing callbacks remain route-owned.
- [x] Create shared secondary-app-bar and feed-filter presentation owners.
  - Evidence: `NavigationChrome.kt` owns the 48dp-minimum back/title/action frame, active-state filter FAB, and dismissible full filter sheet.
- [x] Trace current secondary chrome and feed-filter ownership.
  - Evidence: Codex detail uses text actions without a leading back icon; Creator Profile and Viewer each hand-build different back/title/action rows. Search, For You, and Creator each own compatible modal-sheet framing around route-specific filter controls, and none exposes active filter state on the existing FAB.
- [x] Define the UX-004 implementation boundary before runtime edits.
  - Evidence: `.docs/exec/standardize-secondary-chrome-and-feed-filtering.html` keeps filter values route-owned while sharing only chrome, transient sheet structure, section presentation, and active-state indication.

## Current Task: Reduce Settings First-Open Density

### In Progress

None.

### Pending

- [ ] Manually confirm first-open density and summary readability in the isolated Debug app.

### Done

- [x] Run the focused Settings validation batch and close deterministic UX-003 evidence.
  - Evidence: 9 `SettingsViewModelTest` and 3 `SettingsSummaryPresentationTest` cases passed with zero failures/errors; `:app:compileDebugKotlin` and `:app:detektDebug` passed, with Detekt retaining its known 10 analyzer-resolution diagnostics. HTML validation and `git diff --check` passed. No connected, device, package mutation, or live-provider command ran.
- [x] Implement collapsed defaults and compact live summaries.
  - Evidence: absent expansion keys now default collapsed while explicit persisted booleans remain authoritative. Shared collapsed headers show active-profile, enabled-source, blacklist, account, cache, and developer-scenario state; credentials, weights, and clearing controls remain inside expanded content.
- [x] Trace Settings expansion, persistence, section content, and test ownership.
  - Evidence: `SettingsSectionExpansionState` owns keyed defaults, `SettingsViewModel` restores and persists the full map through `UiRestoreRepository`, and `SettingsScreen` owns all seven shared cards. Missing persisted keys are the only safe default boundary; stored keys remain authoritative.
- [x] Define the UX-003 implementation and acceptance plan before runtime edits.
  - Evidence: `.docs/exec/reduce-settings-first-open-density.html` bounds summaries to repeated-use state and keeps credentials, source weights, and storage clearing controls inside expanded sections.

## Current Task: Preserve the For You Feed Until Explicit Refresh

### In Progress

None.

### Pending

None.

### Done

- [x] Create the task checklist before implementation.
  - Evidence: this section defines the required state flow, restoration behavior, regression coverage, and bounded closeout lane before runtime edits.
- [x] Trace Search restoration and For You refresh ownership; define the minimal repair.
  - Evidence: `ForYouCoordinator` already retains the current feed across route recomposition, but `BrowsingDestinationStateBoundary` mounts the route with placeholder `AppSettings()` and empty likes before repository data arrives. `ForYouViewModel.synchronizeEnvironment` treats those placeholders as authoritative, clears the retained feed at zero likes, and refreshes it again on the real emission. The repair belongs at the shared boundary; no durable feed store or ExecPlan is required.
- [x] Gate browsing-route composition on authoritative settings and likes snapshots.
  - Evidence: Search and For You now mount only after settings, liked IDs, and active-profile likes have emitted real repository snapshots; page reconstruction can no longer send synthetic empty data into the retained route owners.
- [x] Add focused boundary regression coverage.
  - Evidence: `BrowsingDestinationReadinessArchitectureTest` locks nullable loading sentinels for all three inputs and rejects the empty-list likes placeholder that cleared For You.
- [x] Run one bounded validation batch and close the task.
  - Evidence: `:app:compileDebugKotlin`, all app debug JVM tests, and `:app:detektDebug` passed; Detekt retained its known 10 analyzer-resolution diagnostics while exiting green. No device, emulator, connected test, package mutation, or live-provider command ran.

## Current Task: Converge Reversible Deletes and Single Copy Feedback

### In Progress

None.

### Pending

- [ ] Manually re-accept the three corrected interactions in the isolated Debug app.

### Done

- [x] Run one bounded validation batch and update UX-002 evidence.
  - Evidence: the integrated run passed 133 core-data, 40 Room, and 421 app JVM tests with zero failures/errors (five core-data and three app opt-in skips), app Android-test compilation, app Detekt, Room Detekt, core-data main-source Detekt, HTML validation, and diff checks. The final Detekt-driven helper extraction was rechecked by the four focused app regression suites. No connected, device, package mutation, release, or live-provider command ran.

- [x] Add focused race, restoration, and feedback-policy regression coverage.
  - Evidence: focused tests reproduce the settings-refresh feedback race, verify exact multi-membership restoration and newer re-add protection in Room, exercise bulk workflow dismissal/Undo, enforce Android-version copy policy, and guard centralized clipboard/snackbar ownership; all focused lanes pass.

- [x] Eliminate duplicate copy feedback across every clipboard entry point.
  - Evidence: all clipboard writes now use one helper; feature-specific success Toasts remain through Android 12L, while Android 13+ relies on its single system-owned clipboard confirmation as required by the supported platform contract. Tags, post URLs, Viewer sharing, and creator links share the policy; focused policy and architecture tests pass.

- [x] Add exact Undo for Codex detail bulk removal through the shell snackbar.
  - Evidence: Codex detail now snapshots exact memberships, removes the selected IDs atomically, reports `Post removed` or a count-aware bulk message through the shell, and restores original saved timestamps/payloads on Undo without replacing a newer re-add. Focused in-memory workflow and Room persistence tests pass.

- [x] Make For You seed hiding deliver Undo even when its settings write refreshes the route.
  - Evidence: seed hide/undo writes now run in a mutation job that route-environment refresh cancellation does not own; the focused `ForYouViewModelTest` suite passes, including a regression that refreshes settings while the hide mutation is suspended and still receives the exact `SeedHidden` payload.

- [x] Trace the three reported runtime paths before patching.
  - Evidence: the For You settings emission can cancel the refresh-owned job that currently carries `SeedHidden`; Codex detail removes memberships directly with no recovery path; the generic `Copied.` string does not exist in app code and is Android 13+ system clipboard feedback triggered by `setPrimaryClip`.

## Current Task: Add Selective Actionable Transient Feedback

### In Progress

- [~] Await manual UX-002 acceptance in the isolated Debug app.

### Pending

- [ ] Confirm snackbar placement, contextual copy, and Undo behavior on an interactive app surface.

### Done

- [x] Run one bounded validation batch and prepare UX-002 for manual acceptance.
  - Evidence: 133 core-data, 39 Room, and 414 app JVM tests passed with zero failures/errors (five core-data and three app opt-in skips); app Android-test compilation, app Detekt, Room Detekt, core-data main-source Detekt, HTML validation, and diff checks passed. Generic core-data Detekt remains red only on inherited findings in untouched migration/settings/legacy test files; no connected, device, package mutation, or live-provider lane ran.

- [x] Make each Recents clear target reversible with exact snapshot restoration.
  - Evidence: `RecentsClearWorkflow` maps Watched, Codex, Searches, and All to independent repository clears and contextual Undo copy; `RecentsRepository.restoreEntries` preserves timestamps, origin, query hashes, section identity, and ordering without replacing newer activity. Focused in-memory and Room tests pass.
- [x] Add one shell-owned snackbar host and actionable feedback boundary.
  - Evidence: the outer `TheoriaAppContent` Scaffold owns the sole `SnackbarHostState` and renders its host above bottom navigation; feature routes request actions through callbacks while existing passive messages remain Toasts.
- [x] Make recommendation-seed hiding reversible through `Seed hidden` with Undo.
  - Evidence: For You carries only newly persisted blacklist entries and the originating profile through typed effects; Undo removes those exact entries through the existing coordinator refresh path. Focused state and ViewModel tests pass.
- [x] Add focused repository, state, route, and architecture regression coverage.
  - Evidence: focused Recents repository/workflow and For You state/ViewModel/coordinator tests pass; an architecture guard covers single-host ownership and the retained passive Toast lane.

- [x] Create and validate the UX-002 ExecPlan from the traced shell, Recents, and For You flows.
  - Evidence: `.docs/exec/actionable-transient-feedback.html` records the shell/feature/data boundaries, exact Undo semantics, failure paths, acceptance checks, and recovery guidance; both active HTML plans pass `html-validate`.
- [x] Close UX-001 manual acceptance.
  - Evidence: the user tested the collection overflow and Codex selection interaction and reported that it looks good; the UX-001 ExecPlan now records complete manual acceptance.
- [x] Trace current feedback, Recents clearing, and seed-blacklisting ownership before the first UX-002 runtime patch.
  - Evidence: `TheoriaAppContent` owns the only outer `Scaffold` and bottom navigation; Recents already has section-specific repository clears but no feedback; For You persists seed entries through `ForYouCoordinator` and emits a passive toast after refresh. Exact Recents restoration requires repository ownership, while snackbar rendering belongs only to the shell.

## Current Task: Make Codex Actions Discoverable Without Card Clutter

### In Progress

None.

### Pending


### Done

- [x] Run one bounded app validation batch and close the ExecPlan.
  - Evidence: full `:app:testDebugUnitTest` passed 409 tests with zero failures/errors and three unchanged opt-in skips; `:app:compileDebugAndroidTestKotlin`, the final focused `:app:compileDebugKotlin`, and `:app:detektDebug` passed, with Detekt retaining its known 10 analyzer-resolution diagnostics. `npx --yes html-validate` and `git diff --check` passed. The first Detekt attempt exposed shared composable size/complexity, which was repaired through focused component extraction rather than suppression. No device, emulator, connected test, package mutation, live provider, release, tag, or push command ran.
- [x] Add focused selection-state regression coverage.
  - Evidence: `CodexEditSelectionTest` covers inactive taps, select/deselect, stale-post reconciliation, exit, and clean restart; 3 tests passed with zero failures/errors/skips.
- [x] Add explicit Codex-detail edit/selection mode with multi-post removal.
  - Evidence: `CodexEditSelection` owns immutable begin/toggle/reconcile/exit transitions; Codex detail switches taps from Viewer opening to selection, renders selected/unselected markers, offers Cancel and count-aware Remove, preserves long-press actions outside edit mode, and routes selected posts through existing idempotent repository membership removal. Focused file diff checks pass.
- [x] Add a compact collection-tile overflow entry point to the existing action sheet.
  - Evidence: each `CodexGridTile` now places one labeled overflow icon in its existing title/item-count row and routes it to the same `actionTarget` sheet as long-press; the focused file diff check passes.
- [x] Create and validate the UX-001 ExecPlan from the traced Codex runtime.
  - Evidence: `.docs/exec/codex-action-discoverability-and-selection.html` records the bounded data flow, UI states, error boundaries, acceptance checks, and recovery guidance; `npx --yes html-validate` passes.
- [x] Trace the collection list, collection detail, action, sharing, navigation, and repository boundaries before the first runtime patch.
  - Evidence: `CodexListScreen` already owns one compact action sheet reached only by tile long-press; `CodexDetailScreen` exposes single-post removal only through a long-press sheet; `TheoriaApp` already routes share/export, search, rename, delete, and repository removal operations. No new persistence or feed-card action surface is required.

## Current Task: Repair Codex Visible-Post Recording Handoff

### In Progress

None.

### Pending

None.

### Done

- [x] Reproduce the remaining failure in the runtime data flow.
  - Evidence: `ViewerRoute` renders from its own active session, but `TheoriaApp` records through `ViewerDestinationState.session`; the latter can be temporarily null while `ViewerSessionRetentionViewModel.handoffTo()` clears retention before the outer owner snapshot catches up, causing `recordVisiblePost` to default to Search/Watched.
- [x] Record visible posts with the authoritative Viewer route session.
  - Evidence: `ViewerRoute` now pairs each visible post with `viewerOwner.session.value` and sends both through one callback; the shell no longer collects or reads a second Viewer session for Recents persistence.
- [x] Add regression coverage for the session-handoff boundary.
  - Evidence: `ViewerRecentsRecordingSourceTest` locks the route-owned callback, shell usage, and removal of duplicate destination session ownership.
- [x] Run one bounded app validation batch and close the ExecPlan.
  - Evidence: 404 app tests passed with zero failures/errors and three unchanged opt-in skips; app compilation and `:app:detektDebug` passed with its known 10 analyzer-resolution diagnostics; `git diff --check` passed. No device, emulator, connected test, package mutation, live provider, release, tag, or push command ran.

## Current Task: Track Watched and Codex Recents Independently

### In Progress

None.

### Pending

None.

### Done

- [x] Confirm the clean committed baseline and required data flow.
  - Evidence: `fa8c6a8` is clean and the write/read/reopen/clear/migration boundaries were traced before the first runtime patch.
- [x] Create the standalone implementation ExecPlan.
  - Evidence: `.docs/exec/recents-independent-codex-membership.html` records scope, migration behavior, validation, decisions, and recovery.
- [x] Add typed Recents section ownership to the shared repository and Viewer contracts.
  - Evidence: normalization keys watched entries by canonical post plus section, reopening preserves metadata inside the selected section, All deduplicates canonical posts, Viewer context persists an optional typed section, and `./gradlew :core-data:test --no-configuration-cache` passed.
- [x] Migrate Room Recents to schema 3 with post-plus-section identity.
  - Evidence: schema 3 keys `recent_watched` by source, post ID, and section; `MIGRATION_2_3` maps current CODEX origins to CODEX and every other row to WATCHED; Room dual-membership, clear/orphan, importer, and schema-test compilation passed in `:core-data-android:testDebugUnitTest :core-data-android:compileDebugAndroidTestKotlin`.
- [x] Route Recents filters, clear actions, reopen behavior, and All activity by section.
  - Evidence: Recents launches persist an explicit typed section, Viewer records and restores only that section, destination/UI routing reads section rather than origin, and 401 app tests passed with three unchanged opt-in skips after updating the durable Gson manifest.
- [x] Complete bounded core-data, Room, app, HTML, and diff validation.
  - Evidence: the corrected CI-aligned Gradle batch passed 571 tests with zero failures/errors and seven unchanged skips; core-data main, Room debug, and app debug Detekt passed; Room/app lint passed; both app and Room compilation owners passed. App and Room Detekt retained their known 10/361 analyzer-resolution diagnostics while exiting green. `html-validate` and `git diff --check` passed.
- [x] Update the ExecPlan, AGENTS.md, and closeout evidence.
  - Evidence: the ExecPlan records the implemented schema/runtime decisions and validation, while AGENTS.md now protects independent Recents section identity. No device, emulator, connected test, package mutation, live provider, release, tag, or push command ran.

## Current Task: Restore Pixiv Native Authorization Callback Compatibility

### In Progress

None.

### Pending

- [ ] Authenticated live Pixiv completion remains unclaimed until the repaired app is exercised through the provider login flow.

### Done

- [x] Trace and repair the authorization-state mismatch at the callback boundary.
  - Evidence: Pixiv's provider-native `pixiv://account/login` handoff may omit the browser callback's OAuth state. That exact callback now accepts an absent state while still rejecting a supplied conflict; the app-owned callback continues to require an exact state. PKCE verifier binding, ten-minute expiry, encrypted durable storage, and one-shot consumption are unchanged.
- [x] Complete bounded host validation.
  - Evidence: focused `PixivPkceSessionStoreTest` passed, `:app:compileDebugAndroidTestKotlin` passed with regression coverage for both the state-less native success and conflicting-state rejection paths, `:app:detektDebug` passed with its known 10 analyzer-resolution diagnostics, and `git diff --check` passed. No device, emulator, live provider, package mutation, release, tag, push, or publish command ran.

## Current Task: Close PR #17 Quality-Audit Review Follow-ups

### In Progress

None. F19–F21 and their bounded integrated host validation are complete; only the clean branch push remains.

### Pending

- [ ] F09 physical Room v1→v2 migration execution remains unclaimed; the Macrobenchmark target does not exercise that instrumentation owner
- [ ] Opt-in authenticated/live-provider paths and Coil protected-header plus animated-media device behavior remain unclaimed unless separately authorized

### Done

- [x] Integrated PR review-follow-up validation
  - Evidence: all seven JVM owners passed 883 tests with zero failures/errors and six unchanged skips (app 398/3, app-logic 60, core-domain 44, core-data 127/3, core-data-android 37, core-sources 205, core-stubs 12). Both Android-test Kotlin compilers, app and Room lint, and app/app-logic/Room Detekt passed; app/Room Detekt retained their known 10/360 analyzer-resolution diagnostics while exiting green. Aggregate Kover XML/verification passed and the F19–F21 range covered 102/121 executable eligible changed lines (84.30%). Ordinary and branch-base hotspot/Detekt-debt gates passed; production duplication remained 195/36,823 lines (0.53%); npm reported zero vulnerabilities; all 36 helper/workflow tests, ExecPlan HTML validation, and working/committed diff checks passed. No device, emulator, package-mutating instrumentation, benchmark, live provider, release mutation, tag, or GitHub thread write ran.

- [x] F21: make animated-duration retries generation/expiry-aware and bounded
  - Commit: `29328da` — `fix(media): retry animated duration enrichment`. Evidence: each explicit same-identity request now advances a short-lived generation, drains a deduplicated candidate snapshot in eight-item batches, and retains only a 128-entry expiring null-decision map aligned with the service's canonical five-minute TTL. Expired nulls retry, successful IDs can re-enter a later generation so refreshed duration-less posts receive the service's positive cache, and active requests still coalesce. Thirteen focused policy/service tests and 27 Search/For You/Creator owner tests passed; app/app-logic Detekt, ExecPlan HTML validation, and diff checks passed. App Detekt retained its known 10 analyzer-resolution diagnostics while exiting green.

- [x] F20: observe live Settings source availability and persist toggles against the current source set
  - Commit: `1547fc2` — `fix(settings): observe live source availability`. Evidence: the Settings owner now renders from the account-backed source-availability flow and validates mutations against that flow's current value, replacing the construction-time Search snapshot. Eight focused owner tests passed, including a late Rule34 XXX availability emission followed immediately by a persisted toggle before the collector could mask a stale-mutation bug. App compilation through the test lane, app Detekt, ExecPlan HTML validation, and diff checks passed; Detekt retained its known 10 analyzer-resolution diagnostics while exiting green.

- [x] F19: preserve richer shared Room post payloads when Recents or its legacy importer receives a partial snapshot
  - Commit: `f8304ab` — `fix(recents): preserve richer shared post payloads`. Evidence: live Recents and legacy import now call one transaction-local shared-post writer. Its field-aware merge accepts known incoming enrichment/update values while preserving absent rich preview/full/media/tag/taxonomy/creator fields. Both focused Robolectric suites passed 18/18 tests (Room Recents 6, importer 12), including independent partial-versus-rich assertions for both write paths. Room Detekt, ExecPlan HTML validation, and whitespace checks passed; Detekt retained its known analyzer-resolution caveat with 360 compiler errors while exiting green.

- [x] F18: eliminate the final repository-wide actionlint/ShellCheck warning without changing release behavior
  - Commit: `961bec8` — `ci(release): write metadata in one environment block`. Evidence: the four existing `VERSION_CODE`, `RELEASE_TITLE`, `RELEASE_TAG`, and `RELEASE_NOTES_FILE` values are emitted at the same post-validation point through one brace-group append to `GITHUB_ENV`; no validation, action version, Gradle, signing, tag, or publication behavior changed. `actionlint .github/workflows/*.yml` now passes with zero findings, superseding the prior SC2129 caveat while preserving its historical evidence. All 36 helper/workflow tests, ExecPlan HTML validation, and whitespace/diff checks pass. No Gradle rebuild, device/emulator, live provider, signing, tag, publish, push, PR, or release-metadata mutation ran.

- [x] F17: repair release R8 JSON-contract verification ownership
  - Commit: `d5956fc` — `fix(release): verify R8 contracts from AGP artifacts`. Evidence: one typed, configuration-cache-safe task consumes each variant's public `SingleArtifact.OBFUSCATION_MAPPING_FILE` provider plus a separately declared required `outputs/mapping/<variant>/seeds.txt` input; the mapping provider carries the R8 task dependency, and no guessed intermediates path, string-named minify dependency, or `doFirst` script capture remains. After moving aside all four generated release/releaseAcceptance mapping directories, the two public verifier tasks rebuilt 90 tasks in 5m20s and each verified 207 durable fields across 40 retained exact classes while proving 13 contract classes unreachable. An isolated strict graph then stored in 5m52s (90 executed) and reused in 5m30s (84 executed, 6 up-to-date), emitting exact `Configuration cache entry stored.` and `Configuration cache entry reused.` acknowledgements and passing both verifiers again. `:app:assembleRelease :app:assembleReleaseAcceptance` completed 151 tasks in 5m21s and each matching finalizer passed with the same counts. Four focused R8 build/workflow contracts plus four configuration-cache contracts passed; all 36 helper/workflow tests, ordinary and branch-base hotspot gates, production duplication at 195/36,650 lines (0.53%), changed `verify.yml` actionlint, ExecPlan HTML validation, and diff checks passed. The tagged release workflow still reports only its known untouched SC2129 at line 35 and continues to enter through `:app:assembleRelease`; no manual Python fallback exists. No device, emulator, benchmark instrumentation, live provider, signing, tag, push, publish, PR, or release-metadata mutation ran, and the accepted `c3391bd` hardware artifacts remain unchanged.

- [x] Final safe physical Macrobenchmark closeout on the accepted branch
  - Evidence: the preserved F10 baseline remains byte-for-byte intact under `build/reports/hardware-closeout/f10-pre-containment-baseline/` (35 files, one 1,564,575-byte JSON with SHA-256 `7f197822bc49836f393a9edfeef0fe6e8e6ac7ff61aebdfbe7145e76facb2566`, and 30 traces). The first host-only preflight failure remains separately preserved; it never installed an app or began instrumentation. Parent then explicitly authorized one corrected attempt using `ANDROID_HOME=/Users/axel/Library/Android/sdk ANDROID_SDK_ROOT=/Users/axel/Library/Android/sdk ./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest`. The existing runner passed direct verification before execution: AndroidJUnitRunner has no configured listener, while transitive SideEffectRunListener bytecode remains inert. Exactly one unlocked physical target was present—Samsung SM-S926U, Android 16/API 36, serial `R5CWC0SXR3A`, non-emulator—and the command passed 4/4 tests with zero failures/errors/skips in 10m04s. All 30 iterations produced fresh Perfetto traces: 10 cold, 10 warm, 5 Search, and 5 Viewer. The fresh 1,462,126-byte JSON SHA-256 is `f63b9de9730038292f85dd3f3783dba639e217853f855448ba4c347ec3d31a4e`; all 35 output files are isolated under `build/reports/hardware-closeout/final-8b61b0c/connected-output/` with matching source/preserved manifests and zero pre-marker files.
  - Safety: fresh pre/post snapshots cover the exact 41-package AndroidX 1.5.0-alpha07 DisablePackages list plus the complete disabled-package set. Both diffs are empty; the disabled set remains 57 packages with unchanged SHA-256 `43bad472643cc38cd4665733cd77d0bafdf693723f261cb5187028a6297e129a`. Fresh instrumentation/result logs and the active Gradle daemon log contain zero actual “Disabling packages,” “Enabling packages,” listener setup, or DisablePackages execution messages. No package cleanup or unrelated package mutation was attempted.
  - Physical comparison against F10 on the same Galaxy/build: cold startup p50/p90 improved from 284.051/296.834 ms to 262.064/278.109 ms (-7.74%/-6.31%); warm p50 improved 64.318→58.492 ms (-9.06%) while p90 rose 92.795→111.678 ms (+20.35%). Search frame CPU was effectively flat at p50 4.147→4.149 ms (+0.04%) and p90 rose 5.876→6.325 ms (+7.65%); signed frame overrun moved -1.939→-1.875 ms at p50 (0.064 ms less headroom) and -0.033→0.411 ms at p90 (crossed zero, so a percentage is misleading). Search RSS-anon fell 142,048/148,651→119,272/121,044 KiB (-16.03%/-18.57%), media loads fell 702/735.8→369/381 (-47.44%/-48.22%), prepares were 26/26→26/27.2 (0%/+4.62%), and first frames were 24/25.6→24/25.2 (0%/-1.56%). Viewer frame CPU improved 5.279/8.897→5.141/8.804 ms (-2.62%/-1.05%); signed overrun improved from -1.446/3.012 to -1.594/2.881 ms (10.23% more p50 headroom and 4.35% lower p90 lateness). Viewer RSS-anon rose slightly 73,704/79,174→74,476/79,802 KiB (+1.05%/+0.79%); prepares and first frames stayed 10/10 at both percentiles, and media loads stayed 270 at p50 with p90 270.6→270 (-0.22%). Search still asserted every visible fixture video playing before/during scrolling, so lower media-load work is infrastructure savings, not reduced simultaneous autoplay.

- [x] Final deterministic host-only acceptance matrix
  - Evidence: the isolated strict 368-task graph passed all requested Gradle owners and stored in 5m13s (368 executed), then reused in 4m06s (354 executed, 14 up-to-date) with exact store/reuse signals. XML totals are 878 tests, zero failures/errors, six explicit skips: app 396/3 opt-in live skips, app-logic 59, core-domain 44, core-data 127/3 retired FILE_BACKED Recents parameters, core-data-android 35, core-sources 205, and core-stubs 12. Both Android-test compilers, app/Room lint, all seven Detekt owners, aggregate Kover, and 94.28% changed-line coverage (1,434/1,521) passed. Ordinary and branch-base hotspot gates pass after `8b61b0c`; duplication is 195/36,650 lines (0.53%), npm has zero vulnerabilities, all 32 helper/workflow tests pass, changed-workflow actionlint passes `device-validation.yml` and `verify.yml`, and the full glob reports only unchanged SC2129 at `main-prerelease.yml:35:9`. HTML and diff checks pass. Seventeen autoplay/player/benchmark guards prove every visible sibling remains active across all five feeds, never-visible zero-prepare, retained/lifecycle no-reprepare, and isolated player/cache/header policy. Both packaged verifiers pass directly: the isolated fixture contains the exact 32,502-byte local asset (SHA-256 `ac3213320cd1c8acbf081cb13ce652c8706dcda37e3a18bc1af31e23a5335403`) and the runner has no listener configuration. The 4,816,604-byte releaseAcceptance APK SHA-256 is `ba0af4dd64a19b571604066c8af1d19e825f56e302c51994f5008d7d520b2be4`; both actual AGP mappings plus output seeds verify 207 durable fields across 40 retained classes and 13 removed classes. The ordinary release wrapper remains unclaimed because its expected `mapping.txt` is absent while `mapping.prt` and seeds are present. The ordered chain runs from F01 `61dd92c` through F16 and accepted follow-ups to `8b61b0c`; status contains only the two living-ledger edits. No adb/device/emulator query, benchmark instrumentation, live provider, release mutation, tag, push, publish, or PR ran.

- [x] F15 review repair: compare Detekt branch debt by stable logical owner
  - Commit: `8b61b0c` — `fix(quality): compare Detekt debt by stable owner`. Evidence: the ratchet safely parses and removes only leading declaration annotations, preserving rule, file, simple class/member nesting, and the remaining callable/class declaration; malformed annotations, unsupported declarations, and logical-owner collisions fail closed, while exact per-rule counts remain unchanged. Fifteen focused tests cover annotation addition/removal/reordering, the actual SearchScreen signature change, rule/file/nesting/callable mutations, collisions, and malformed input. The completed branch passes `python3 scripts/check_hotspots.py --base 213d9e9`; all 32 helper/workflow tests, the ordinary hotspot audit, production duplication at 195 lines / 0.53%, zero-vulnerability npm audit, HTML validation, and diff checks passed. No workflow changed, so actionlint was not applicable. No device, emulator, benchmark instrumentation, live provider, release build, push, or PR command ran, and final acceptance was not resumed.

- [x] F16 review follow-up: make CI fail closed unless the second strict help invocation reuses configuration cache
  - Commit: `43a8182` — `ci(android): assert configuration cache reuse`. Evidence: the Verify workflow runs both strict help invocations with plain console output in one isolated project cache, tees the second log under `set -euo pipefail`, and exits unless `grep -Fqx` finds the exact `Configuration cache entry reused.` line. The strengthened four-test configuration-cache contract locks the pipefail, retained-log, exact-signal, and explicit-failure boundaries. The same shell mechanism locally emitted `Reusing configuration cache.` and `Configuration cache entry reused.` after a 770ms store and 472ms reuse; all 28 helper tests, `actionlint` on `verify.yml`, ExecPlan HTML validation, and diff checks passed. No device, emulator, benchmark instrumentation, final matrix, live-provider, release, push, or PR command ran.

- [x] F16: independently evaluate Coil 3 compatibility and strict Gradle configuration-cache adoption
  - Commit: `b7f9085` — `build(android): evaluate image loading and configuration cache`. Evidence: official Coil docs identify 3.5.0 as current stable and confirm separate network/cache-control artifacts, new header/extras/cache-key APIs, `Image` conversion, singleton-loader ownership, painter `StateFlow`, and default-size changes. An isolated detached-worktree compile reproduced breakage across Theoria's loader factory, custom decoder result/options, protected per-request headers, crossfade/SVG, and Viewer drawable seams. Coil remains at 2.7.0 because this finding cannot run the required protected-header plus animated-media device evidence; a two-test architecture gate makes any future major upgrade deliberate. Gradle configuration cache is enabled repository-wide in strict fail mode. Help, the 231-task representative app/core graph, and both custom packaged-artifact verifiers store/reuse with zero problems after removing script-object captures from the two verifier tasks. Five identical warm up-to-date samples measured 1.37s median without configuration cache versus 0.72s with reuse (47.4% lower; 1.63s store); this measures local configuration/task-graph overhead, not clean compilation or CI speed. The final strict 312-task host graph stored then reused and passed all 878 JVM tests with zero failures/errors and six unchanged skips, both Android-test compilers, app/Room lint, seven Detekt owners, Kover, and both packaged APK contracts. Hotspot budgets, 0.53% duplication, zero-vulnerability npm audit, 28 helper/workflow tests, changed-line coverage (no eligible platform-free F16 lines), changed-workflow `actionlint`, HTML, and diff checks passed. A full workflow-glob `actionlint` also surfaced an unchanged style-only SC2129 in `main-prerelease.yml`; F16 did not modify that release workflow. No emulator, connected, physical, benchmark instrumentation, release, or live-provider command ran.

- [x] F15: split monolithic tests and ratchet hotspot, Detekt-debt, and duplication ownership
  - Commit: `eed9ade` — `test(quality): ratchet hotspot ownership and coverage`. Evidence: Hitomi (1,342 lines), file-backed repositories (1,151), NHentai (930), SearchViewModel (918), and live Search routing (745) are now behavior-named suites with one shared fixture per owner; all 115 named tests remain exactly present and the repository's largest test is 640 lines. A fail-closed Python gate discovers every Kotlin-bearing Gradle module, treats every non-test source set as production, caps tests at 700 lines and new production at 900, freezes all nine inherited production hotspots at their checked size, requires the configured Detekt-baseline set to match the repository, holds exact module/rule counts, and rejects new baseline IDs against the CI base. Eleven focused gate tests cover malformed/missing configuration, omitted modules/source sets/baselines, hotspot growth/moves, test growth, and baseline count/identity drift. Nine stale app baseline IDs were removed and one active update-prompt LongMethod was extracted, reducing app debt from 69 entries (30 cognitive, 38 long, 1 large) to 59 (27 cognitive, 32 long); every other module/rule count is unchanged. The duplication ceiling tightened from 0.70% to 0.60%, with the audit green at 195 lines / 0.53% (0.07-point headroom). All 876 module JVM tests passed with zero failures/errors and six unchanged intentional skips: app 394/3 skipped, app-logic 59, core-domain 44, core-data 127/3 skipped, core-data-android 35, core-sources 205, and core-stubs 12. Both Android-test owners compiled; app and Room lint, all seven Detekt owners, aggregate Kover XML/verification, 24 helper/workflow tests, npm audit, HTML validation, and diff checks passed. The changed-line gate inspected no eligible platform-free production line because F15's only runtime extraction is in Android `app`; the dedicated app tests, compilation, lint, and Detekt cover it. App/Room Detekt retained their known 10/354 compiler-resolution diagnostics while exiting green. No emulator, connected, physical, benchmark, release, or live-provider command ran.

- [x] F14: localize destination state collection behind lifecycle-aware route boundaries
  - Commit: `acded24` — `refactor(app): localize destination state collection`. Evidence: `TheoriaAppContent` now directly observes only the global AppShell owner; lifecycle-aware child boundaries own Settings, credentials, Recents, Codex list/detail/save, browsing preferences/membership, and retained feed/Viewer state. Navigation, incoming intents, startup/update/install/platform effects, action callbacks, weak retained feed handles, and Viewer session/paging identity remain shell-stable. A one-composition Robolectric test proved after each independent Settings, Recents, Codex, and credential mutation that only the matching child recomposed while the parent shell count remained fixed. All 394 app JVM tests passed (zero failures/errors, three intentional skips), as did all 59 app-logic tests, main/Android-test compilation, app lint, app/app-logic Detekt, aggregate Kover, changed-coverage helper tests, HTML validation, and diff checks. The repository changed-line gate had no eligible F14 lines because it intentionally covers platform-free modules rather than Android `app`; focused structural and recomposition tests cover this boundary. Duplication passed at 195 lines / 0.53%, npm reported zero vulnerabilities, and app Detekt retained its existing 10 compiler-resolution diagnostics while exiting green. No emulator, connected, physical-device, release, or live-provider command ran.
- [x] F13: extract platform-free application logic and include it in changed-line coverage
  - Commit: `refactor(architecture): extract platform-free app logic`. Evidence: the new Kotlin/JVM `:app-logic` module owns Search execution/state contracts and reducers, the shared scoped-input parser/messages, visibility/filtering, feed activation/decode policy, provider-independent media classification/duration, recommendation taxonomy, and animated-duration candidate/drain scheduling. Android route/ViewModel/service/Compose/Media3/Coil/lifecycle ownership and provider URL/header normalization remain in `:app`; dependencies are limited to `:core-domain`, `:core-data`, and coroutines. Pixiv Ugoira MIME now has one `:core-domain` wire owner plus a source compatibility alias. Fifty-eight app-logic tests and 392 app JVM tests passed with zero failures/errors and three intentional app skips; main/Android-test compilation, lint, app/app-logic Detekt, aggregate Kover, coverage-helper tests, 0.54% duplication, npm audit, HTML, and diff checks passed. The staged changed-line gate passed at 93.06% (228/245 executable lines; 491 eligible production lines inspected). Architecture guards prove the dependency/moved-owner/quality-gate boundary and prevent the shared Search policy from duplicating again. App Detekt exited green with 23 compiler-resolution diagnostics. No connected, physical-device, or live-provider command ran.
  - Follow-up: `perf(media): reuse progressive source policy`. Evidence: the four-source progressive-image allowlist is allocated once instead of on every per-card policy check. Four architecture tests and three PostMedia tests passed with app compilation, lint, Detekt, HTML validation, and diff checks; no device command ran.
- [x] F12: make Search route state ownership authoritative in one owner
  - Commit: `refactor(search): make route state ownership authoritative`. Evidence: SearchViewModel now exclusively owns Search route state, execution identity, initial/page/retry jobs, continuation, accepted-result persistence, stale-result rejection, provider-status merging, canonical dedupe, retry transitions, and one-time restoration. SearchCoordinator is an immutable execution/persistence service with no observable snapshot or competing query/results/status/loading/token state; the old mapper is deleted. Identity-mismatched or superseded results clear loading and cannot publish or persist, admitted root failures apply and record the requested query while retaining prior content and disabling old paging, and page success/failure merges statuses by source. Sixty-five focused boundary tests and all 446 app JVM tests passed (three intentional skips), together with main/Android-test compilation, lint, Detekt, HTML validation, and diff checks. Detekt exited green with its existing 10 compiler-resolution diagnostics. No connected, physical-device, or live-provider command ran.
- [x] F11: improve concurrent autoplay performance while every visible video card keeps playing
  - Commit: `0a37e3b` — `perf(feed): optimize concurrent autoplay previews`. Evidence: all five feeds converge on one viewport/lifecycle-aware media card that defers the first player prepare until visibility, retains the same paused player while composed offscreen, stops animated drawables/Ugoira work when inactive, and preserves simultaneous autoplay for every visible sibling. Application-owned Media3 infrastructure shares base HTTP/local factories plus a 64 MiB cache, isolates protected headers through hashed request identities, and creates fresh per-player load controls from one bounded 6–12-second / 6 MiB policy. Card images decode near display size under 1,600 × 2,400 px ceilings, and Codex now has stable canonical item keys. Twenty-three focused JVM tests plus app/Macrobenchmark and Android-test compilation, lint, Detekt, HTML validation, and diff checks passed. Detekt still reports 10 compiler-resolution diagnostics while exiting successfully. No connected/physical command ran by user direction; F10's pre-containment Galaxy metrics remain the denominator and no post-F11 numeric improvement is claimed.
- [x] F10: add numeric startup and concurrent-feed-autoplay Macrobenchmarks
  - Commit: `355e4aa` (`test(performance): measure startup and concurrent autoplay`). Evidence: a separate Macrobenchmark module drives an isolated `.benchmark` application with offline real Search/Viewer media surfaces, stable IDs, bounded waits, ten cold/ten warm startup iterations, and five Search/five Viewer interaction iterations. Search requires at least two visible videos and asserts every visible fixture player remains active; Viewer asserts the active player through repeated forward/reverse swipes. Nineteen focused JVM tests passed with benchmark/baseline/app and Android-test compilation, benchmark lint, Detekt, packaged-APK verification, and production-manifest isolation. The safe app APK has no Internet/install permission, VIEW/BROWSABLE/App Links, or FileProvider. The runner has no configured `listener` argument or instrumentation-manifest listener metadata; transitive `SideEffectRunListener`/`DisablePackages` classes remain packaged but inert. A pre-containment Galaxy S24+ / Android 16 run passed 4/4 and retained a 1,564,575-byte JSON plus 30 Perfetto traces; cold startup p50/p90 was 284.05/296.83 ms, warm 64.32/92.79 ms, Search frame CPU 4.147/5.876 ms with 26 prepares and 24 first frames at median, and Viewer frame CPU 5.279/8.897 ms with 10 prepares/first frames. Those numbers are not post-containment proof: the completed run exposed that AndroidX Benchmark 1.5.0-alpha07's configured listener disables and blindly re-enables 41 unrelated packages. Its runner argument is now removed, no automatic phone cleanup was attempted, and user direction freezes further device execution; the safe configuration therefore has deterministic build/artifact proof with a physical rerun gap.
  - Follow-up: `e663907` (`test(performance): verify benchmark listener stays unconfigured`). Evidence: source contracts reject a `listener` runner argument and assembled-runner verification proves the instrumentation manifest uses `AndroidJUnitRunner` with no listener metadata. Required transitive listener classes remain packaged but inert; no device command ran.
- [x] F09: migrate Recents activity persistence to Room
  - Commit: <code>f36ed49</code> (<code>refactor(recents): migrate activity history to Room</code>). Evidence: the shared Room database is the sole live owner of watched/search activity; schema 2 adds shared-post foreign keys, stable versioned query payloads, durable equal-time sequence, transactional 200/100 caps, origin-preserving updates, independent clears, merged activity order, and orphan cleanup that respects Codex/Likes ownership. The verified one-time importer preserves legacy order, rows plus proof atomically, exact deterministic archives, all tested crash windows, and F08 quarantine while failing closed on source, destination, proof, archive, or conflicting Room state. Sixteen focused Room Recents tests plus query-codec, Gson/R8, and app-ownership guards passed. The integrated 120-task matrix passed 127 core-data tests (3 intentional skips), 35 core-data-android tests, and 443 app tests (3 intentional skips), app/Room lint, app/Room/core-data Detekt, both Android-test compiles, and schema export. Detekt exited successfully while reporting analyzer-resolution caveats (9 app and 354 Room compiler diagnostics). A physical Galaxy S24+ / Android 16 attempt exposed and fixed the Room test APK's missing AndroidJUnitRunner dependency; the rebuilt suite compiled, but the one rerun found no connected device, so physical v1→v2 migration execution remains explicit and unclaimed.
- [x] F08: quarantine unreadable legacy JSON instead of silently overwriting it
  - Commit: `f9ae5a7` (`fix(storage): quarantine unreadable legacy state`). Evidence: present malformed, empty, null, or invalid UTF-8 whole-file JSON is preserved byte-for-byte under a deterministic filename/byte-count/SHA-256 quarantine before a safe default can escape; matching archives finish interrupted recovery, while collisions, I/O, adapter, cancellation, and preservation failures propagate without overwriting the live file. One application registry covers Query, Recents, updater, and tag suggestions, re-verifies quarantines after process restart, and feeds full typed diagnostics to the Settings owner; Settings renders one compact recovery sentence without the full path/hash or a destructive reset. Seventy-four focused JVM tests, all 131 core-data tests, and 442 app unit tests (439 passed, 3 pre-existing skips) passed with app lint, app/core-data Detekt, Android-test compilation, HTML validation, and diff checks. App Detekt exited successfully but continued to report 23 compiler-resolution errors during analysis, leaving its type-resolution accuracy as a build-tooling caveat. The focused Settings semantics test passed on a physical Galaxy S24+ running Android 16; this is UI execution only, not performance, release, or live-provider evidence. The installed API 37 AVD was unusable because its saved configuration reported an unsupported ARM architecture.
- [x] F07: bound Hitomi random/search memory with primitive storage and deterministic continuation
  - Commit: `1c0c30b` (`perf(hitomi): bound random search memory`). Evidence: Nozomi decoding and random/search membership/page paths are primitive; canonical sort/dedupe prevents repeated raw IDs across pages; one 16 MiB snapshot cache is reused across seeds; membership is capped at 8 MiB, global indexes at 32 MiB, known sizes and suggestion counts at 256 KiB each, and media failure guards at 64 KiB. Version 4 tokens bind query, snapshot, seed, affine algorithm version, offset, and global-index version. Seventy-eight focused Hitomi tests and all 205 core-sources tests passed with strict core/app Detekt, app lint, Android-test compilation, and Gson compatibility. Release R8 completed and the direct mapping proof retained 206 exact fields across 40 classes while removing 13 unreachable contract classes. The Gradle wrapper verifier still targets an obsolete mapping path, so the same checked verifier was run directly against AGP's actual intermediate mapping and seeds. This is deterministic JVM/build evidence; no connected-device, physical-device, or live-provider proof is claimed.
- [x] F06: centralize animated-duration enrichment with bounded single-flight work
  - Commit: `65716eb` (`refactor(media): centralize animated duration enrichment`). Evidence: Search, For You, and Creator composables now emit typed requests to route-owned bounded drain lanes backed by one application-owned service with cross-route single-flight, a three-work concurrency cap, 128-entry positive/negative LRU caches, five-minute negative expiry, authoritative adapter resolution, and cancellation-preserving probing. Eighty-nine focused JVM tests passed across the service, all three route owners/contracts, filtering/media behavior, and structural ownership guards; both Android-test owners compiled; app lint and Detekt passed. This is deterministic JVM/build evidence only; no connected-device, physical-device, or live-provider retriever execution is claimed.
- [x] F05: consolidate Search query and scroll persistence and delete the orphan persistence path
  - Commit: `d0c5011` (`refactor(search): consolidate query and scroll persistence`). Evidence: UI restore is the sole live scroll store; prior query-file offsets migrate once with proof and structural cleanup; the production ViewModel coalesces query-keyed positions, excludes temporary scopes, serializes begun writes, and drains one final value during real owner clear before cancelling its scheduler. One hundred fifty-nine focused JVM tests, both Android-test compilation owners, app lint, app/core-data Detekt, HTML validation, and `git diff --check` passed. No connected-device or physical-device claim is needed for this deterministic persistence finding.
- [x] F04: centralize Settings state and persisted section expansion ownership
  - Commit: `069b705` (`refactor(settings): centralize settings state ownership`). Evidence: one Settings owner replaces seven shell expansion booleans and the 44-parameter screen boundary with keyed durable expansion plus immutable state/typed actions. Thirty-one focused owner, credential, recovery, and ownership tests and 45 repository persistence/reopen tests passed; app lint, Detekt, Android-test compilation, HTML validation, and `git diff --check` passed. The four-test Compose class compiles, but the new owner-contract test was not device-executed because no device is attached and the API 37 AVD registry entry has no backing configuration; the unchanged header retains F02's accepted API 37 geometry evidence.
- [x] F03: keep persisted source API keys out of saveable UI state
  - Commit: `411aece` (`fix(settings): keep saved API keys out of UI state`). Evidence: stored credentials now map through a replace-only presentation that exposes user ID and Configured status but always emits a blank API-key field; blank saves reuse the repository key, replacements supersede it, and unconfigured blank saves fail closed. Six policy tests plus 18 encrypted-store/recovery tests passed; Android-test compilation, app lint, Detekt, HTML validation, and `git diff --check` passed.
- [x] F02: enlarge compact feed controls and add explicit accessibility state semantics
  - Commit: `333e7fb` (`fix(ui): improve compact control accessibility`). Evidence: Search like, For You source, and Settings header controls expose 48dp clickable geometry with checkbox/dropdown/button roles and selected or expanded state descriptions while preserving 30dp and 18dp visuals. The focused three-test Compose class passed on an API 37 emulator after the shared semantics extraction; app unit tests, `:app:lintDebug`, `:app:detektDebug`, Android-test compilation, and `git diff --check` passed.
- [x] F01: execute every Android instrumentation owner in CI and correct device artifact naming
  - Commit: `61dd92c` (`ci(android): execute Room schema validation on devices`). Evidence: device CI now runs and uploads reports for both `:app` and `:core-data-android`; the API 35 release-acceptance artifact is named accurately. Two workflow contract tests, `actionlint`, `:core-data-android:compileDebugAndroidTestKotlin`, and `git diff --check` passed.
- [x] Launch the dedicated implementation task and hand it the sequential commit contract
- [x] Complete the read-only 2026-07-31 audit and establish current validation baselines
- [x] Record the non-negotiable product constraint that all visible video cards continue autoplaying
- [x] Create and validate `.docs/exec/code-quality-ux-performance-reliability-remediation.html`
  - Evidence: the plan maps F01 through F16 to sequential commits, exact validation, recovery, and living-log requirements; `html-validate` and `git diff --check` pass.

## Current Task: Preserve Settings Collapse State

### In Progress

-

### Pending

### Done

- [x] Lift Settings section expansion state to the app-shell owner.
  - Evidence: `TheoriaAppContent` owns seven `rememberSaveable` flags and passes the state/update boundary into `SettingsScreen`, so top-level page recreation no longer resets sections to expanded.
- [x] Persist Settings section expansion across app relaunches.
  - Evidence: `UiRestoreRepository` stores the section expansion map in the DataStore-backed UI restore record, with in-memory and file-backed contract coverage.
- [x] Validate locally without connected-device testing.
  - Evidence: `:core-data:test --tests 'com.theoriacodex.data.repository.RepositoryContractTest' --tests 'com.theoriacodex.data.repository.DataStoreRepositoriesTest'`, `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `git diff --check` passed.

## Current Task: Reorder Settings Sections

### In Progress

-

### Pending

### Done

- [x] Reorder the Settings sections and apply the requested capitalization.
  - Evidence: the UI order is Recommendation Profiles, Unified Mode, For You Blacklist, Source Accounts, Updates, and Storage & Caching; optional Developer scenarios remains last.
- [x] Validate locally without connected-device testing.
  - Evidence: `:app:compileDebugKotlin` passed.

## Current Task: Make Settings Sections Collapsible

### In Progress

-

### Pending

### Done

- [x] Wrap every Settings section in the shared collapsible header pattern.
  - Evidence: Recommendation Profiles, For You blacklist, Unified mode, Source Accounts, Storage & caching, Updates, and Developer scenarios each have independent `rememberSaveable` expanded state and a right-aligned chevron.
- [x] Validate locally without connected-device testing.
  - Evidence: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `git diff --check` passed.

## Current Task: Hide Unified Excluded Source Chips

### In Progress

-

### Pending

### Done

- [x] Keep disabled-source diagnostics in Unified execution state while removing their non-actionable chips from the Search UI.
  - Evidence: `visibleSourceStatusChipStatuses` filters `EXCLUDED` statuses while retaining provider failures, with focused unit coverage.
- [x] Validate locally without connected-device testing.
  - Evidence: `SourceFailureUiTextTest`, `:app:compileDebugKotlin`, and `git diff --check` passed.

## Current Task: Preserve Search Scroll During Universal Pagination

### In Progress

-

### Pending

### Done

- [x] Trace the result-grid, route restoration, and page-append flow.
  - Evidence: `SearchScreen` keyed scroll restoration to `visibleResults.size`, causing every appended page to replay the persisted route-entry position for all sources.
- [x] Separate one-time route restoration from pagination updates.
  - Evidence: restoration remains pending until results exist, then is consumed; result-count changes cannot replay it.
- [x] Validate the source-agnostic fix.
  - Evidence: `:app:testDebugUnitTest` focused Search ViewModel/state tests, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, and connected `SearchSourceChipDeviceTest` on SM-S926U / Android 16 all passed.

## Current Task: Complete Hitomi Character Search Crash Repair

### In Progress

### Pending

### Done

- [x] Reproduce the still-failing search from current device state.
  - Evidence: the persisted debug query is `character:klee`; fresh logs again show duplicate canonical key `HITOMI:4076681`, and the live route reproduced 25 hydrated cards with only 24 unique identities.
- [x] Move uniqueness enforcement to Hitomi's shared hydrated-post boundary.
  - Evidence: all Hitomi search routes now remove repeated canonical `Post.id` values after metadata hydration, covering global index, typed Nozomi facets, random ordering, and creator/search callers without changing raw-record pagination offsets.
- [x] Add exact deterministic and live regression coverage.
  - Evidence: a fixture models both repeated raw IDs and distinct raw IDs hydrating to `HITOMI:4076681`; the live `character:klee` route now publishes only unique post identities.
- [x] Verify the installed app against the saved failing query.
  - Evidence: installed `app-debug.apk` over `com.theoriacodex.debug`, cold-started into the persisted Klee result grid, scrolled repeatedly, and confirmed the same process remained alive with no `AndroidRuntime` exception.
- [x] Complete the final regression and integrity batch.
  - Validation: full `:core-sources:test` and `:app:testDebugUnitTest` passed within a combined 598 tests, 0 failures, 0 errors, and 3 opt-in skips; the exact opt-in live Hitomi route, `:app:assembleDebug`, HTML validation, and `git diff --check` also passed.

## Current Task: Restore Search Chip Styling And Fix Hitomi Crash

### In Progress

### Pending

### Done

- [x] Restore the original Material `FilterChip` styling without changing the temporary multi-source interaction.
  - Evidence: `ModeRow` renders the original `FilterChip`; a transparent sibling owns tap and source-only long press without replacing Material colors, borders, shape, or padding. `SearchSourceChipDeviceTest` passed 1/1 on SM-S926U (Android 16), including physical tap timing and long-press isolation.
- [x] Capture and repair the one-tag Hitomi crash at the provider boundary.
  - Evidence: `com.theoriacodex.debug` throws `IllegalArgumentException: Key "HITOMI:4076681" was already used` while measuring the Search staggered grid, proving duplicate Hitomi `PostId` values reached the keyed UI list.
  - Initial repair: deduplicated gallery IDs inside the global-index record. Follow-up evidence proved this was insufficient because the actual saved query used typed Nozomi data and separate raw IDs could hydrate to one canonical identity; the current task records the complete repair.
- [x] Add deterministic and live regression coverage.
  - Evidence: the fixture global-index test proves `[4, 3, 4, 2]` becomes `[4, 3, 2]`; the opt-in live `girl` route completed successfully and asserted unique post identities.
- [x] Complete the bounded regression batch.
  - Validation: full `:core-sources:test` and `:app:testDebugUnitTest` passed within a combined 597 tests, 0 failures, 0 errors, and 3 opt-in skips; `:app:compileDebugAndroidTestKotlin`; `:app:assembleDebug`; connected `SearchSourceChipDeviceTest`; live Hitomi route test; and `git diff --check` passed.

## Feature Closeout: Temporary multi-source Search

- [x] Complete the final verification and docs-only closeout.
  - Evidence: cherry-picked `ef6b012`, `2539a00`, and `654ff683` in order; the complete diff was independently reviewed against `.docs/exec/temporary-multi-source-search.html` with no product defect found and no production file changed during closeout.
  - Validation: focused state/ViewModel tests 22/22; focused `SearchCoordinatorTest` 59/59; full `:app:testDebugUnitTest` 409 tests, 0 failures, 0 errors, 3 opt-in skips; `:app:compileDebugAndroidTestKotlin`; `:app:assembleDebug`; working-tree and committed-range `git diff --check`; `npx --yes html-validate .docs/exec/temporary-multi-source-search.html`; and connected `SearchSourceChipDeviceTest` 1/1 on SM-S926U (Android 16).

- [x] Normalize the ten baseline-only ExecPlan HTML findings.
  - Evidence: compared against baseline `8540503`; normalized the lowercase doctype, self-closing void tags, and invalid `aria-label` uses in `.docs/exec/temporary-multi-source-search.html` only. HTML validation now passes.

- [x] Render source chips directly from `draftSourceScope` with one combined click/long-click accessibility owner.
  - Evidence: `SearchSourceChipDeviceTest` passed on the connected SM-S926U (Android 16); 1 test, 0 failures. Focused JVM Search coverage passed 81 tests (59 `SearchCoordinatorTest`, 10 `SearchViewModelTest`, 12 `SearchStateContractTest`); Android-test compilation, debug assembly, whitespace, and HTML validation also passed.

## Implementation History: Phases 1 And 2 Of Temporary Multi-Source Search

### In Progress

### Pending


### Done

- [x] Phase 2: Route the frozen temporary source set through exact Unified execution
  - Evidence: root/retry/paging use the applied scope captured in `RootSearchRequest`; autocomplete/trending use the draft explicit scope; selected sources bypass global enablement without Settings mutation; source-owned terms are sanitized; unavailable sources collapse safely; temporary query/history/scroll persistence is skipped; scope-aware execution identity is preserved through Viewer/hash state.
  - Validation: `ANDROID_SDK_ROOT=/Users/axel/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests '*SearchCoordinatorTest'` — 59 tests, 0 failures. Combined focused Search lane — 81 tests, 0 failures.

- [x] Trace Search mode selection, route state, coordinator execution, paging, restoration, and persistence boundaries
  - Evidence: `ModeRow` currently emits only `SelectMode`; `SearchCoordinator` owns both single-source and Unified execution, while `RootSearchRequest` already freezes the enabled source set for root search and paging.
- [x] Define the temporary multi-source interaction and lifecycle
  - Evidence: the plan keeps explicit source selection route-scoped, uses the unified orchestrator without mutating settings, and defines normal tap, long-press add/remove, collapse, reset, retry, source-loss, and process-recreation behavior.
- [x] Create the standalone implementation ExecPlan
  - Evidence: `.docs/exec/temporary-multi-source-search.html` contains exact file ownership, phased work, automated and manual acceptance, recovery, and living-log sections.
- [x] Validate the planning artifacts
  - Evidence: HTML structure, required section anchors, referenced repository paths, and whitespace integrity were checked locally.

## Previous Task: Implement Code Quality And UI Ownership Modernization

### In Progress

### Pending

### Done

- [x] Phase 7: enforce quality budgets, complete system acceptance, and close the program
  - [x] 7A: make coverage, complexity, duplication, dependency, and architecture budgets durable
    - Evidence: Kover reports 55.57% aggregate line coverage against a 55% floor and the exact staged core diff passes 85.71% against the 60% changed-line threshold; missing eligible sources fail closed; strict Detekt 15/60/600 thresholds use checked historical baselines; architecture source guards, Dependabot, dependency submission, and the 0.7% duplication ceiling are active
  - [x] 7B: close lint, manifest, orientation, and resource correctness findings
    - Evidence: app lint fell from 81 findings to nine dependency-update signals with no remaining source/resource correctness warning or hint; Room lint is clean; obsolete Rule34 assets, resource qualifiers, icon/monochrome metadata, manifest filters, orientation policy, modifier order, and primitive Compose state are corrected
  - [x] 7C: complete the final behavior and ownership review
    - Evidence: updater install identity/reconciliation, intent handoff, FileProvider grants, durable one-shot PKCE, cancellation-safe terminal completion, keyed ViewModel route leases, and architecture boundaries have focused JVM/device tests; the final independent staged review found no blocker
  - [x] 7D: close current-facing documentation and audit metrics
    - Evidence: README and AGENTS now identify the application container, route owners, production stores/migrations, deterministic/live/device lanes, quality budgets, release optimizer, and retained follow-ups; the ExecPlan records final hotspot, duplication, lint, coverage, test, device, startup, and migration evidence
  - [x] Phase 7 gate
    - Evidence: the 307-task integrated Gradle gate passed core/app tests, app and Room lint, Android-test compilation, strict Detekt, aggregate Kover verification, debug/release APKs, and the R8 mapping proof; 772 JVM executions report zero failures/errors and three opt-in skips; production duplication is 206 lines / 0.66%; the final six-test PKCE device class passed on API 37

- [x] Phase 6: modernize persistence, credentials, and Android tooling in reversible waves
  - [x] 6A: migrate settings and UI restoration to typed asynchronous DataStore files
    - Evidence: both stores import legacy JSON once with schema/hash/count proofs, archive only after verification, fail closed on newer schemas, bound retained records, and publish the application graph only after readiness without blocking `Application.onCreate`
  - [x] 6B: promote the transactional Room Codex/Likes prototype after the Kotlin 2 compiler wave
    - Evidence: Room 2.8.4 owns Codices, Likes, versioned post snapshots, ordering, and migration proofs in one schema-v1 database; 19 Robolectric transaction/migration tests and the exported-schema Android test compile pass
  - [x] 6C: replace the live credential writer with a versioned Android Keystore envelope
    - Evidence: AES-256-GCM snapshots are bounded, atomically written, read-back verified, backup-excluded, retry-safe across legacy migration, and never auto-deleted on corruption or key mismatch; Android tests compile while device execution remains queued
  - [x] Phase 6 storage wave gate
    - Evidence: `./gradlew lint test :app:assembleDebug :app:compileDebugAndroidTestKotlin --rerun-tasks` passed 110 tasks and 1,099 configured test executions with 0 failures/errors; lint reports 111 known warnings
  - [x] 6E: migrate Kotlin and Compose compiler tooling
    - Evidence: Kotlin 2.0.21, the matching Compose compiler plugin, typed JVM compiler options, and explicit JSpecify compilation passed a clean 110-task gate with 1,099 configured test executions; this is the newest metadata level supported by the current AGP 8.5 lint/R8 boundary
  - [x] 6B integration: finish reviewed Room hardening, register the module, and move Codex/Likes to one transactional owner
    - Evidence: application readiness imports and verifies the legacy JSON pair before archiving either source; one repository instance backs both interfaces and cross-boundary workflows, preserving manual Likes-Codex saves while making toggles, clears, profile deletion, reorder, and bulk import atomic
  - [x] Phase 6 Room gate
    - Evidence: the normal parallel full gate passed 229 tasks and 747 configured test executions with debug/release APKs, app and Room lint, Room/app Android-test compilation, 0 failures/errors, 86 app warnings, and 1 Room compile-SDK warning; a final lossless-migration review then added a green 10-test importer gate covering every key-bearing row, duplicate, relationship, future schema, and archive crash window
  - [x] 6F: advance Android packaging to AGP 9.1.1 and Gradle 9.3.1 on JDK 17
    - Evidence: clean lint/tests, debug APK, release APK, and Android-test compilation passed 131 tasks; the wrapper checksum is pinned and the temporary external-Kotlin opt-out is explicit for the mixed Android/JVM graph
  - [x] 6E follow-up: advance from the green Kotlin 2.0 rollback point to Kotlin 2.4.0 on the compatible AGP toolchain
    - Evidence: JDK 17 clean lint/tests, debug/release packaging, and Android-test compilation passed 131 tasks and 729 configured test executions; AGP lint reads the final metadata and reports 86 warnings
  - [x] 6D: migrate Android libraries and target SDK after storage integration
    - Evidence: compile/target SDK 37 and the reviewed stable AndroidX, Compose, DataStore, Room, Media3, Security Crypto, Coroutines, Gson, jsoup, and test versions passed the clean 230-task gate with 750 configured tests, 0 failures/errors, 3 skips, debug/release APKs, Android-test compilation, 75 app lint warnings plus 6 hints, and clean Room lint; the Android 17 behavior review found no production MessageQueue/static-final reflection, LAN, SMS, Contacts, Bluetooth, writable dynamic-native-loading, background-media, or implicit URI-grant dependency, while large-screen orientation cleanup remains assigned to 7B
  - [x] 6G: validate release shrinking and startup optimization
    - Evidence: shipping and acceptance variants share R8/resource rules; both mapping checks retained all 205 durable JSON fields across 40 reachable types and removed 13 unreachable contract types; generated profiles contain 32,360 baseline and 28,852 startup rules; API 37 and API 27 each passed 31 device executions with zero failures and one intentional provider-live skip; the minified non-debuggable acceptance APK passed cold-start and Pixiv-callback launches with an empty crash buffer

- [x] Phase 5: consolidate repeated policy and stable UI primitives
  - [x] 5A: share pure repository policy and run one contract suite across both backends
    - Evidence: file-backed and in-memory repositories apply the same pure policy; the parameterized contract suite passed across both backends while durability mechanics remain independent
  - [x] 5B: centralize recommendation-tag and source-weight normalization
    - Evidence: training/serving affinity keys and source-weight totals have one owner; explicit zero weights are preserved unless the entire distribution is degenerate
  - [x] 5C: share provider mechanics without hiding provider-specific protocol behavior
    - Evidence: safe JSON, HTTP classification, network conversion, challenge matching, duration parsing, and strict decoding are shared while each adapter retains its authentication and fallback semantics
  - [x] 5D: extract stable post-action, feed-state, grid, and autocomplete UI primitives
    - Evidence: Search, For You, Creator, and Codex share behavior-equivalent primitives; Viewer playback and fixed Codex collection behavior remain specialized
  - [x] 5E: centralize source presentation and derive operational capability from interfaces
    - Evidence: one catalog owns labels/logos/exposure/order, creator browsing is interface-derived, AIBooru is explicitly adapter-only, and production duplication fell from 1.02% to 0.38%
  - [x] Phase 5 gate
    - Evidence: 110 tasks passed with 1,052 configured test executions, 0 failures/errors, 6 opt-in skips, 119 known lint issues, and compiled Android-test sources

- [x] Phase 4: move UI ownership to route-scoped state holders
  - [x] 4F: freeze route owner, effect host, saved-state, and feature-factory contracts
    - Evidence: every major route exposes immutable state plus typed actions/effects; narrow weak leases reject work only after their navigation ViewModel is cleared
  - [x] 4A: create the Search route owner and state-driven rendering boundary
    - Evidence: Search owns restoration, resume, environment reconciliation, request jobs, effects, and one immutable screen entry; cross-route actions queue until the lazy page is available
  - [x] 4B: create the Viewer route owner while keeping platform media handles outside state
    - Evidence: Viewer consumes its Activity handoff once, owns live-source merging/paging and stale-session effect rejection, and fails closed when durable reconstruction is unavailable
  - [x] 4C: create For You and Creator route owners with deterministic request ownership
    - Evidence: both routes own refresh/page generations and expose read-only state to Viewer; creator identity is handed to the route rather than prepared as shell-owned coordinator state
  - [x] 4D: extract startup, incoming URI, Codex transfer, likes sync, and Viewer-session workflows
    - Evidence: named app-shell/update/Codex services have platform-free tests; Viewer cross-route persistence/restoration policy lives in a dedicated workflow bridge
  - [x] 4I: integrate route owners/workflows into navigation and remove legacy duplicate ownership
    - Evidence: `TheoriaApp.kt` fell from the 2,839-line audit baseline to 2,742 lines; the 110-task gate passed 1,005 configured test executions with 0 failures/errors and 6 opt-in skips, lint remained at 120 known issues, and Android-test sources compiled; connected execution is deferred because ADB has no device

- [x] Phase 3: establish the application container and immutable UI contracts
  - [x] 3A: define immutable Search state, typed actions/effects, and coordinator mapping
    - Evidence: 10 pure mapping/reducer tests cover loading, replacement, retry, paging, cancellation, draft preservation, restoration, and Viewer effects
  - [x] 3B: define immutable Viewer state, typed actions/effects, and platform-handle boundary
    - Evidence: 11 reducer tests cover mixed media, session replacement, resolution, playback/frame controls, overview, prefetch, typed effects, and same-session stale failures without Android/player handles in state
  - [x] 3C: define immutable For You and Creator state/action contracts
    - Evidence: 15 feed-contract tests cover mapping, selection, empty/error lanes, paging, typed failure, cancellation, and generation rejection of stale root/page work
  - [x] 3D: move concrete construction into one application-owned container with observable source availability
    - Evidence: application-owned data/source/update/feature bundles retain registry/coordinator identity; credential-gated Rule34 capability hydrates before Search restoration and changes without graph reconstruction
  - [x] 3I: integrate the container into the app shell and make Flow collection lifecycle-aware
    - Evidence: shell resolves four dependency bundles, uses lifecycle-aware Flow collection and STARTED-scoped Codex collectors, exposes TheoriaAppContent, retains the Viewer handoff in an Activity-scoped owner, and coordinator initialization is idempotent across configuration notification
  - [x] 3R: close final integration-review races
    - Evidence: For You and Creator cancel/supersede active root/page generations before capability or settings replacements; non-cooperative late provider results cannot repopulate unavailable state
  - [x] Phase 3 gate
    - Evidence: 110-task deterministic gate passed 895 test executions with 0 failures/errors and 6 opt-in skips; container identity passed earlier on-device, while the compiled recreation/shell tests await a runnable target after USB/ADB disconnected

- [x] Phase 2: bound transport, caches, and disk work
  - [x] 2A: cap text HTTP responses and unify GET/POST cleanup and cancellation
    - Evidence: 8 MiB bounded text transport shares GET/POST cleanup and active disconnect-on-cancel; focused 16-test HTTP suite and final 172-test core-sources suite passed
  - [x] 2B: replace Hitomi's global index map with a versioned byte-weighted single-flight LRU
    - Evidence: 32 MiB injectable byte-weighted LRU evicts by access, separates versions, coalesces loads, preserves cancellation and active-query IDs, and binds v3 pagination tokens to the index version; focused and full core-sources suites passed
  - [x] 2C: move repository file work onto injected IO and share atomic JSON replacement
    - Evidence: repositories dispatch file/cache work to injected IO, share hardened atomic JSON, and roll visible state back after failed/cancelled writes; 72 core-data tests passed
  - [x] 2D: adopt atomic, bounded, batched app file stores
    - Evidence: updater transitions use one atomic suspend transform; tag suggestions hydrate asynchronously, debounce 500 ms, cap at 25,000/source and 4 MiB total, and flush non-cancellably on close; 24 focused store/updater tests passed
  - [x] 2I: conflate Search scroll and suggestion persistence without composition-time disk work
    - Evidence: scroll bursts debounce, final state flushes on effect disposal, commits cannot be cancelled between compatibility stores, suggestion hydration completes before Search initialization, and one application-owned suggestion store prevents overlapping writers across Activity recreation; 54 Search coordinator plus 4 scroll lifecycle tests passed
  - [x] 2 gate: full validation and installed-app smoke
    - Evidence: 110-task gate passed 793 configured tests with 0 failures/errors and 6 opt-in skips; final debug APK installed/cold-launched as pid 26753 with an empty crash buffer

- [x] Phase 1: repair correctness invariants
  - [x] 1A: preserve cancellation across app and provider suspend boundaries
    - Evidence: shared helper rethrows the same cancellation; For You, Creator, Search, Ugoira, NHentai, Rule34Video, and Rule34Paheal focused cancellation tests passed across core-domain/core-sources/app
  - [x] 1B: make hydrated Codex snapshots durable when membership already exists
    - Evidence: sparse-to-hydrated duplicate `addItem` survives reconstruction without changing order/timestamp; exact no-op avoids disk rewrite
  - [x] 1E: exclude credentials from backup/transfer and expose recoverable reconnect state
    - Evidence: legacy and Android 12+ rules exclude encrypted credential preferences from cloud/device transfer; corruption enters `ReconnectRequired` without deletion, and reset requires explicit confirmation
  - [x] 1C: migrate last-tab persistence to one `UiRestoreRepository` owner
    - Evidence: empty restore imports one trimmed legacy value, later restore wins, file-backed migration survives reconstruction, and the app no longer writes the legacy Settings owner
  - [x] 1D: make Search latest-request-wins and cancellation-safe
    - Evidence: generation snapshots cancel/supersede older root/page work, serialize final applied persistence, and gate every publication; delayed-first, stale-page, and external-cancellation tests pass
  - [x] 1I: integrate sole tab ownership, credential recovery, and cancellation behavior in `TheoriaApp`
    - Evidence: full 110-task gate passed 738 configured tests with 0 failures/errors and 6 opt-in skips; debug APK installed/cold-launched with a live process and empty crash buffer
- [x] Phase 0: establish trustworthy guardrails
  - [x] 0A: add deterministic PR/main verification workflow
    - Evidence: reusable YAML parsed; core modules and app lint/unit/android-test compilation/debug assembly passed
  - [x] 0B: classify offline/live Android tests and diagnose the Compose smoke harness
    - Evidence: offline Hitomi parser and Animated WebP device tests passed; live Hitomi transport skips without explicit opt-in; smoke failure is a locked/dozing device lifecycle (`CREATED`, not `RESUMED`), not an unknown missing hierarchy
  - [x] 0C: consolidate test fixtures and expand characterization coverage
    - Evidence: shared fixtures plus repository backend contracts, For You failure/blacklist cases, and updater retry/deferral cases passed (`core-data`: 50 tests; app: 232 tests, 3 opt-in skips)
  - [x] 0I: integrate tagged-SHA release verification, run the phase gate, and update the ExecPlan
    - Evidence: `./gradlew lint test :app:assembleDebug :app:compileDebugAndroidTestKotlin --rerun-tasks` passed 110 tasks and 701 configured test executions with 0 failures/errors and 6 opt-in skips; offline Hitomi device parser passed
- [x] Confirm the approved plan commit is the current baseline (`57bcb15`)
- [x] Read the repository ExecPlan standard and orchestration skills
- [x] Preserve the existing release-skill edit and completed audit evidence
- [x] Define the phased architecture, dependency order, and conflict-safe parallel agent lanes
- [x] Draft `.docs/exec/code-quality-ui-ownership-modernization.html` with exact files, commands, acceptance criteria, recovery guidance, and a finding-coverage matrix
- [x] Reconcile independent UI-ownership and quality/testing planning reviews into the plan
- [x] Validate the ExecPlan with `html-validate`, verify audit-finding coverage, and pass `git diff --check`

## Current Task: Current Code Quality Audit

### In Progress

### Pending

### Done

- [x] Read repository guidance, current system documentation, and orchestration skills
- [x] Confirm the pre-audit worktree state and preserve the existing release-skill edit
- [x] Map module boundaries, production hotspots, and recent architectural growth
  - Evidence: 49,216 Kotlin/Kotlin-script lines across production and tests; the largest ownership boundaries are `TheoriaApp`, `ViewerScreen`, `SearchScreen`, `SearchCoordinator`, `FileBackedRepositories`, and `HitomiSourceAdapter`
- [x] Audit duplication, repeated policy, and oversized ownership boundaries
  - Evidence: `jscpd` found 17 production Kotlin clones, 341 duplicated lines (1.02%), and 2,359 duplicated tokens (1.20%) across 99 files; policy drift matters more than bulk copy-paste
- [x] Audit unit, integration, UI, device, and live-provider test coverage
  - Evidence: roughly 455 distinct tests, with strong provider fixtures but thin app-shell, updater, authentication, recommendation, and Android platform coverage
- [x] Run focused static/build/test validation and collect measurable baselines
  - Evidence: `./gradlew lint test :app:assembleDebug --rerun-tasks` passed all 102 tasks; 681 configured test executions completed with 0 failures, 0 errors, and 6 skips
- [x] Run the connected Android lane and distinguish deterministic from live coverage
  - Evidence: three device tests passed, including the Hitomi protocol checks; `TheoriaAppSmokeTest.appShellRendersTopLevelNavigation` failed reproducibly because no Compose hierarchy was available
- [x] Reconcile findings into a prioritized remediation roadmap
- [x] Record audit evidence and closeout state without changing production code

### Blocked / Findings

- [!] The connected UI smoke lane is not currently a green, hermetic release signal; repair the Compose test harness and separate offline Android parser coverage from live-provider checks

## Current Task: Implement Hitomi Source, Faceted Search, And Mixed Media

### In Progress

### Done
- [x] Correct the remaining Hitomi global-search, animated-overview, and creator-sharing gaps
  - [x] Mirror Hitomi's versioned galleries B-tree for unqualified All terms such as `girl`
  - [x] Autoplay visible Animated WebP entries in Media Overview while keeping non-animated posters static
  - [x] Add Share link and Copy link actions to Creator Profile
  - [x] Update the active ExecPlan and developer runtime contracts
  - [x] Pass deterministic source/viewer tests, the opt-in live app route, all JVM tests, and two Hitomi device tests
  - [x] Install and cold-launch `com.theoriacodex.debug` explicitly; keep production installed separately and confirm an empty crash buffer
- [x] Restore Hitomi-global All searches and add honest animated-WebP playback controls
  - [x] Resolve exact global terms such as plain `Gyaru` to Hitomi's `female` facet only during Nozomi compilation
  - [x] Preserve the original unprefixed portable query in UI, persistence, and page-token hashing
  - [x] Route every Hitomi animated WebP page through the bounded controllable decoder on all supported APIs
  - [x] Add play/pause, restart, and live frame progress without advertising unsupported arbitrary seeking
  - [x] Remove the opaque black source-chip background while retaining the supplied transparent PNG byte-for-byte
- [x] Make Hitomi gallery entry and faceted Search vocabulary immediate and discoverable
  - [x] Open sparse Hitomi Search results immediately on their animated WebP preview and resolve the full gallery in the Viewer background
  - [x] Keep deep-link, Codex, and Recents resolution behavior unchanged when no current Search preview can bridge the load
  - [x] Render plain `All` terms without a misleading `Tag` prefix
  - [x] Add a source-owned featured-facet contract and expose Hitomi Type and Language values before typing
- [x] Fix Hitomi `gamecg` and multi-tag searches on Android's ranged binary transport
  - [x] Reproduce `gamecg` failure on the physical Galaxy through `HitomiSourceAdapter` as Android gzip `EOFException`
  - [x] Require identity encoding for every shared binary byte-range request
  - [x] Keep non-success HTTP responses from reopening a throwing input stream when no error body exists
  - [x] Pass live `gamecg` and `animated` + `female:x-ray` adapter searches on the physical Android 16 device
  - [x] Confirm production `com.theoriacodex` and instrumentation target `com.theoriacodex.debug` are separate installed packages
- [x] Fix the connected-device startup crash caused by Android ICU rejecting the startup-loaded Hitomi gallery-assignment regex
  - [x] Replace the regex with deterministic declaration and object-body scanning
  - [x] Pass the focused Hitomi protocol instrumentation test on a physical Android 16 device
  - [x] Pass the animated-WebP instrumentation test on the same API 28+ device
  - [x] Cold-launch `com.theoriacodex/.app.MainActivity`, keep the process alive past 10 seconds, and confirm an empty crash buffer
- [x] Phase 7: Add targeted health/app smoke, expose Hitomi after the live gate, document current behavior, and close the plan (`./gradlew lint test :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks`: 449 tests, 0 failures, 3 opt-in live skips)
  - [x] Preserve typed artist probes, scoped autocomplete, optional trending, exact diagnostic URLs, and cancellation propagation in provider health
  - [x] Support both historical and current inverted `gg.js` shard polarity without accepting an ambiguous routing shape
  - [x] Pass strict targeted provider health with 11/11 Hitomi steps `OK`, including exact galleries and bounded WebP/MP4 ranges
  - [x] Expose Hitomi only after the health gate, then pass all 5 targeted app-smoke class tests (3 network routes plus 2 harness invariants) for Search, Creator Profile, overview mapping, media headers, cancellation, and source filtering
  - [x] Update README and AGENTS with faceted search, mixed media, Nozomi, mutable CDN, cancellation, decoder, and video-prefetch contracts
  - [x] Verify the supplied Hitomi logo remains byte-identical at SHA-256 `a970eb56124e1237e3b4ee18494f2a159e02bc0f9bcd95a910140ec8e852187d`
  - [x] Complete physical Android 16 startup, Hitomi parser, and animated-WebP acceptance; API 26/27 execution remains unavailable
- [x] Phase 6: Generalize the Viewer Media Overview and support animated WebP/anime video (`./gradlew :core-domain:test :core-data:test :core-sources:test :core-stubs:test :app:testDebugUnitTest :app:processDebugManifest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks`: 435 tests, 0 failures, 2 opt-in live skips)
  - [x] Classify explicit animated images independently from videos and preserve exact ordered media indices
  - [x] Render still, animated, GIF, Ugoira, and video poster/badge tiles without overview autoplay
  - [x] Decode animated WebP natively on API 28+ and through a bounded, interruptible API 26/27 fallback
  - [x] Decode Hitomi WebP overview posters as separately cached static first frames on every API
  - [x] Cache only complete videos up to 16 MiB and leave large/partial MP4s on Media3 ranged streaming
  - [x] Preserve selected gallery page index/count and save Hitomi anime once as MP4 with source headers
  - [x] Run the generated two-frame WebP instrumentation test successfully on a physical Android 16 device; API 26/27 fallback execution remains unavailable
- [x] Phase 5: Add creator browsing, typed post actions, and Hitomi deep links (`./gradlew :core-domain:test :core-data:test :core-sources:test :core-stubs:test :app:testDebugUnitTest :app:processDebugManifest :app:compileDebugKotlin --rerun-tasks`: 421 tests, 0 failures, 2 opt-in live skips)
  - [x] Delegate paginated Hitomi Creator Profile streams to exact typed artist search and preserve every distinct valid artist
  - [x] Share one canonical Unicode-safe Hitomi artist identity contract across provider metadata, persistence-facing UI admission, and deep links
  - [x] Preserve exact post taxonomy through Search, Viewer, Codex, and Creator actions without widening favorites/recommendations beyond general tags
  - [x] Switch source mode only when a source-owned post term requires it, and leave unavailable-source actions visibly unchanged
  - [x] Route Hitomi reader, gallery, anime, CG, and artist HTTP/HTTPS links for both supported hosts
- [x] Phase 4: Implement and audit Hitomi search, pagination, hydration, and media URL resolution behind the release exposure gate (`./gradlew :core-domain:test :core-data:test :core-sources:test :core-stubs:test :app:testDebugUnitTest`: 400 tests, 0 failures, 2 opt-in live skips)
  - [x] Make Random source-wide with one bounded snapshot, v2 fingerprinted tokens, bounded LRU continuity, and changed-snapshot fail-closed behavior
  - [x] Reject candidate-less/empty galleries and type every bounded Nozomi/CDN failure while preserving per-gallery isolation
  - [x] Connect exact-404 one-refresh CDN recovery to Search and Viewer through a generic source capability
  - [x] Register the real adapter while keeping `exposedRealSources()` closed until Phase 7
  - [x] Add exact Nozomi range paging, faceted include/exclude compilation, bounded hydration, and sparse Search cards
  - [x] Resolve ordered galleries, animated-page metadata, anime MP4s, and mutable AVIF/WebP/original CDN candidates
  - [x] Migrate upgraded settings once and preserve later user source-disable choices
  - [x] Copy `/Users/axel/Downloads/hitomi-logo.png` byte-for-byte into the source chip asset
- [x] Phase 3: Build reusable scoped autocomplete for NHentai and the Search UI (`./gradlew :core-sources:test :app:testDebugUnitTest`: 288 tests, 0 failures, 2 opt-in live skips)
  - [x] Add source-owned scope selection, typed suggestion actions/chips, raw prefix parsing, and Unified-mode protection
  - [x] Make NHentai compile typed facets while preserving language, full-color, direct-ID, and same-name Tag/Artist behavior
  - [x] Migrate suggestion caching to source + facet + namespace + normalized-value identity
  - [x] Keep recommendations and seen-tag learning general-tag-only while preserving Pixiv's native raw-tag preference
- [x] Phase 2: Add typed query/post taxonomy, safe legacy persistence, portable Codex snapshots, and Unified facet boundaries (`./gradlew :core-domain:test :core-data:test :core-sources:test :app:testDebugUnitTest`: 483 tests, 0 failures, 4 opt-in live skips); commit `067da0f`
  - [x] 2.1 Add canonical domain terms, typed suggestions, post taxonomy, and facet-aware query hashing
  - [x] 2.2 Add backward-compatible query/post/recents persistence records and malformed-record recovery
  - [x] 2.3 Preserve typed post snapshots through Codex share/import and offline fallback without exporting local paths
  - [x] 2.4 Migrate app/source query writers, close combined-review findings, and verify the integrated phase
- [x] Phase 1: Freeze Hitomi protocol fixtures and add binary HTTP primitives (`./gradlew :core-sources:test`: 100 tests, 0 failures); commit `0724ced`
- [x] Read `.docs/PLANS.md`, the active Hitomi ExecPlan, repository guidance, and orchestration skills
- [x] Confirm the implementation worktree is clean before phase edits (`git status --short --branch`)
- [x] Verify the new HTML ExecPlan structure, required sections, links, and diff (`npx --yes html-validate`, required-section scan, `git diff --check`)
- [x] Write the Hitomi source, faceted-search, creator, and mixed-media ExecPlan from live route evidence
- [x] Confirm the agreed UX direction and inspect the current query, suggestion, creator, source, and Viewer boundaries
- [x] Create the current-task checklist without removing historical work

## Pending
- [ ] Manual device acceptance for Recents behavior

## In Progress

## Done

- [x] Phase 1: Add the route-level draft/applied source-scope contract
  - Evidence: `SearchSourceScope` distinguishes global Unified, single source, and canonical temporary sets; typed `ToggleTemporarySource` updates draft state and selection-only edits enable Apply without changing Compose, Settings, `QueryMode`, or repositories.
  - Validation: `ANDROID_SDK_ROOT=/Users/axel/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests '*SearchStateContractTest' --tests '*SearchViewModelTest'` — 22 tests, 0 failures.
- [x] Run final live source verification (`./gradlew :core-sources:test`, `./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true`, `./gradlew :app:testDebugUnitTest -Ptheoria.liveSources=true --tests '*LiveSearchCoordinatorRouteTest*'`, `./gradlew lint test :app:compileDebugAndroidTestKotlin`)
- [x] Fix Rule34Video live trending degradation (`./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true`)
- [x] Fix NHentai live trending degradation (`./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true`)
- [x] Fix Iwara live autocomplete degradation (`./gradlew :core-sources:test`)
- [x] Implement live source coverage Phase 5: reporting, docs, and final verification (`./gradlew lint test :app:compileDebugAndroidTestKotlin`, `./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true`, `./gradlew :app:testDebugUnitTest -Ptheoria.liveSources=true --tests '*LiveSearchCoordinatorRouteTest*'`)
- [x] Implement live source coverage Phase 4: media URL reachability smoke (`./gradlew :app:testDebugUnitTest --tests '*LiveSearchCoordinatorRouteTest*'`)
- [x] Implement live source coverage Phase 3: app SearchCoordinator route smoke (`./gradlew :app:testDebugUnitTest --tests '*LiveSearchCoordinatorRouteTest*'`)
- [x] Implement live source coverage Phase 2: source-level live health runner (`./gradlew :core-sources:test :core-sources:providerHealthCheck`)
- [x] Implement live source coverage Phase 1: probe cases and result model (`./gradlew :core-sources:test :core-sources:providerHealthCheck`)
- [x] Create live source routing and seeded-search coverage ExecPlan
- [x] Implement code quality Phase 6: final regression audit and documentation (`./gradlew lint test :app:assembleDebug`, `./gradlew :app:testDebugUnitTest lint`, `./gradlew :app:assembleDebug`, `git diff --check`, `jscpd`)
- [x] Implement code quality Phase 5: modernize tests and add UI smoke coverage (`./gradlew :core-data:test :core-domain:test :core-stubs:test`, `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`; connected smoke skipped with no adb device)
- [x] Implement code quality Phase 4: split `TheoriaApp.kt` dependency graph (`./gradlew :app:testDebugUnitTest :app:assembleDebug`)
- [x] Implement code quality Phase 3: consolidate media request and download policy (`./gradlew :app:testDebugUnitTest`, `./gradlew lint`)
- [x] Implement code quality Phase 2: harden file-backed persistence (`./gradlew :core-data:test`, `./gradlew test`)
- [x] Implement code quality Phase 1: restore green lint (`./gradlew lint`, `./gradlew :app:testDebugUnitTest :core-sources:test`)
- [x] Create code quality hardening and modularization ExecPlan
- [x] Register code quality hardening ExecPlan in `AGENTS.md`
- [x] Implement Recents tab after plan approval
- [x] Replace Explore with Recents UI/navigation (`./gradlew :app:testDebugUnitTest`)
- [x] Update docs and run focused verification (`./gradlew :app:compileDebugKotlin :app:assembleDebug`)
- [x] Record applied searches and watched posts (`./gradlew :app:testDebugUnitTest --tests '*SearchCoordinatorTest*'`)
- [x] Add Recents repository models, persistence, and tests (`./gradlew :core-data:test`)
- [x] Confirm Recents UX decisions: Watched default, search history v1, full-list Viewer taps
- [x] Draft Recents tab ExecPlan replacing Explore and removing quick queries
- [x] Milestone 6: final provider-message polish, docs, plan evidence, and broad verification (`./gradlew :app:testDebugUnitTest`, `./gradlew test`)
- [x] Milestone 5: split viewer session and Codex share policy out of `TheoriaApp.kt` (`./gradlew :app:testDebugUnitTest`)
- [x] Milestone 4: add opt-in live provider health reporting and Settings state (`./gradlew :core-sources:test :core-data:test :app:testDebugUnitTest :core-sources:providerHealthCheck`, `./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true`)
- [x] Milestone 3: add provider contracts and central provider helpers (`./gradlew :core-sources:test :core-stubs:test`)
- [x] Milestone 2: share media and clipboard selection policy across app surfaces (`./gradlew :app:testDebugUnitTest`)
- [x] Milestone 1: preserve saved post duration and progressive image URLs (`./gradlew :core-data:test`)
- [x] Refresh implementation checklist from maintainability/reliability ExecPlan
- [x] Verify documentation changes
- [x] Register the new ExecPlan in `AGENTS.md`
- [x] Add maintainability and reliability ExecPlan
- [x] Refresh task checklist and inspect ExecPlan conventions
- [x] Refresh task checklist and inspect current code
- [x] Add Codex tag option helper and helper tests
- [x] Wire source tag options into TheoriaApp and apply behavior
- [x] Build Codex tag picker UI with randomize and floating Apply
- [x] Update README recent updates
- [x] Run Gradle verification and inspect diff
