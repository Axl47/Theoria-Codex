---
created_at: 2026-05-31T00:13:56Z
updated_at: 2026-07-26T06:12:08-04:00
---
# Working List

## Current Task: Implement the 2026-07-31 UX, Performance, Reliability, and Quality Audit

### In Progress

-

### Pending

- [ ] Run the final deterministic, Android-device, performance, and documentation acceptance matrix
- [ ] Update the ExecPlan, `working_list.md`, and any durable `AGENTS.md` findings after every commit

### Done

- [x] F16: independently evaluate Coil 3 compatibility and strict Gradle configuration-cache adoption
  - Commit: `build(android): evaluate image loading and configuration cache` (SHA recorded after commit). Evidence: official Coil docs identify 3.5.0 as current stable and confirm separate network/cache-control artifacts, new header/extras/cache-key APIs, `Image` conversion, singleton-loader ownership, painter `StateFlow`, and default-size changes. An isolated detached-worktree compile reproduced breakage across Theoria's loader factory, custom decoder result/options, protected per-request headers, crossfade/SVG, and Viewer drawable seams. Coil remains at 2.7.0 because this finding cannot run the required protected-header plus animated-media device evidence; a two-test architecture gate makes any future major upgrade deliberate. Gradle configuration cache is enabled repository-wide in strict fail mode. Help, the 231-task representative app/core graph, and both custom packaged-artifact verifiers store/reuse with zero problems after removing script-object captures from the two verifier tasks. Five identical warm up-to-date samples measured 1.37s median without configuration cache versus 0.72s with reuse (47.4% lower; 1.63s store); this measures local configuration/task-graph overhead, not clean compilation or CI speed. The final strict 312-task host graph stored then reused and passed all 878 JVM tests with zero failures/errors and six unchanged skips, both Android-test compilers, app/Room lint, seven Detekt owners, Kover, and both packaged APK contracts. Hotspot budgets, 0.53% duplication, zero-vulnerability npm audit, 28 helper/workflow tests, changed-line coverage (no eligible platform-free F16 lines), changed-workflow `actionlint`, HTML, and diff checks passed. A full workflow-glob `actionlint` also surfaced an unchanged style-only SC2129 in `main-prerelease.yml`; F16 did not modify that release workflow. No emulator, connected, physical, benchmark instrumentation, release, or live-provider command ran.

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
