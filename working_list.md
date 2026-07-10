---
created_at: 2026-05-31T00:13:56Z
updated_at: 2026-07-10T18:40:37-04:00
---
# Working List

## Current Task: Implement Code Quality And UI Ownership Modernization

### In Progress

### Pending

- [ ] Phase 3: establish the application container and immutable UI contracts
- [ ] Phase 4: move UI ownership to route-scoped state holders
- [ ] Phase 5: consolidate repeated policy and stable UI primitives
- [ ] Phase 6: modernize persistence, credentials, and Android tooling in reversible waves
- [ ] Phase 7: enforce quality budgets, complete system acceptance, and close the program

### Done

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
