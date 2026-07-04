---
created_at: 2026-02-24T18:08
updated_at: 2026-02-25T06:02
---
# Build Theoria Codex Android MVP (Stub-first, Local-first)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository includes `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`; this document follows that file and must be maintained in accordance with it.

## Purpose / Big Picture

Theoria Codex must become a functioning Android app where users can discover content with tag queries, stage query edits in Draft state, apply explicitly to fetch results, browse in a masonry grid, open immersive Viewer, and save posts into persistent Codices with offline thumbnails. MVP must run end-to-end using stub adapters so real-source integration can be added later without breaking domain models or UI contracts.

The success path is observable: launch app, switch among Search/Explore/Codex/Settings, run a query, open Viewer, save to Codex, restart, and verify persisted state and saved items.

## Progress

- [x] (2026-02-24 22:07Z) Created `working_list.md` and started execution tracking.
- [x] (2026-02-24 22:07Z) Authored initial ExecPlan from the spec and multi-agent strategy.
- [x] (2026-02-24 22:13Z) Completed Milestone 1 scaffold: Gradle wrapper, Android app module, core modules, bottom-nav placeholder shell, portrait lock.
- [x] (2026-02-24 22:13Z) Verified baseline build with `./gradlew tasks --all` and `./gradlew assembleDebug`.
- [x] (2026-02-24 22:18Z) Completed Milestone 2: implemented domain models/contracts, `QueryHash`, Draft/Applied query state machine primitives, source capability gate helpers, and unit tests.
- [x] (2026-02-24 22:15Z) Updated `AGENTS.md` with surprising files and added `README.md` documenting scaffold/module layout and local commands.
- [x] (2026-02-24 22:16Z) Re-validated after config/docs updates with `./gradlew assembleDebug :core-domain:test`.
- [x] (2026-02-24 22:31Z) Completed Milestone 3: added persistence/cache repository contracts plus in-memory and file-backed implementations with tests (`:core-data:test`).
- [x] (2026-02-24 22:31Z) Completed Milestone 4: added stub fixture corpus, scenario-aware source adapters, and unified weighted orchestration with tests (`:core-stubs:test`, `:core-domain:test`).
- [x] (2026-02-24 22:31Z) Re-validated project with `./gradlew :core-domain:test :core-stubs:test :core-data:test assembleDebug`.
- [x] (2026-02-24 23:52Z) Completed Milestone 5 parity pass: added query-hash scroll restoration wiring, tightened reset/apply semantics, and added Search->Viewer launch handoff with `ViewerLaunchContext`.
- [x] (2026-02-24 23:00Z) Completed Milestone 6: implemented fullscreen Viewer (swipe/pinch-pan/double-tap/chrome auto-hide/info sheet), Codex list/detail screens, Save-to-Codex sheet, and end-to-end save/remove flows.
- [x] (2026-02-24 23:03Z) Completed Milestone 7: implemented Settings screen for enabled source toggles, normalized weights, cache controls, and stub scenario switching with immediate runtime effect.
- [x] (2026-02-24 23:05Z) Completed Milestone 8: added new app/core unit tests for viewer state transitions, search restoration behavior, codex sorting/dedup + persistence, and settings normalization/scenario persistence.
- [x] (2026-02-24 23:07Z) Final validation gate passed:
  - `./gradlew :core-domain:test :core-stubs:test :core-data:test`
  - `./gradlew testDebugUnitTest`
  - `./gradlew assembleDebug`
  - `./gradlew lintDebug`
- [x] (2026-02-25 06:02Z) Completed post-MVP UX/behavior pass: rounded single-line Search bar with inline hint, Enter-to-add-tag behavior, focus-clearing interactions, in-sheet animated-only staging, source pagination (`load more`) with near-end auto-fetch, animated-only empty-page fallback paging, Viewer `Go to Search` close-and-navigate fix, and incremental version bump to `0.1.1`.
- [x] (2026-02-25 01:28Z) Completed second-pass contract refactor: `SourceAdapter` autocomplete support, `requiresCredentials` capability, typed `SourceFailureReason`, and `SourceAdapterRegistry` abstraction.
- [x] (2026-02-25 01:33Z) Added new `:core-sources` module with real Pixiv/AIBooru/Gelbooru adapters and `RealAdapterRegistry` (Phase A runtime exposure: Pixiv only).
- [x] (2026-02-25 01:37Z) Rewired app runtime from stubs to real registry in `SearchCoordinator` and `TheoriaApp`; source lists are now registry-driven and unfinished sources are hidden.
- [x] (2026-02-25 01:39Z) Added secure source credential storage (`AndroidSecureSourceCredentialsStore`) and Pixiv PKCE callback plumbing (`MainActivity` deep-link callback + Settings account controls).
- [x] (2026-02-25 01:41Z) Second-pass validation gate passed:
  - `./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test`
  - `./gradlew testDebugUnitTest`
  - `./gradlew assembleDebug`
  - `./gradlew lintDebug`
- [x] (2026-02-25 04:31Z) Added startup updater implementation for `main` prerelease channel: GitHub Releases feed parser, APK download/validation/installer flow, persisted update state, splash-gated startup orchestration, and Android installer/FileProvider manifest plumbing.
- [x] (2026-02-25 04:36Z) Added `.github/workflows/main-prerelease.yml` to publish `main-vc<versionCode>-<sha>` prereleases with fixed update asset `theoria-codex-main.apk`; added update parser tests and re-ran app build/unit-test gates.

## Surprises & Discoveries

- Observation: Repository initially had no app/build scaffold.
  Evidence: Initial `rg --files` returned only planning/spec markdown files.

- Observation: First Android build triggered automatic installation of Build-Tools 34.
  Evidence: `./gradlew assembleDebug` installed `Android SDK Build-Tools 34.0.0` before compilation.

- Observation: AGP 8.5.2 warns for compileSdk 35 compatibility.
  Evidence: Build output emitted unsupported compileSdk warning; mitigated by setting `android.suppressUnsupportedCompileSdk=35` in `gradle.properties`.

- Observation: Public constructor signatures in `:core-sources` surfaced `Gson` to app compile classpath.
  Evidence: `:app:compileDebugKotlin` failed until `gson` was added to app dependencies or signatures were hidden behind internal API.
- Observation: Clearing pending installer state immediately after launching Android installer caused avoidable re-downloads when users canceled install and reopened app.
  Evidence: Startup updater would not reuse previously downloaded APK unless `pendingInstallReleaseId` remained set for the same release.

## Decision Log

- Decision: Execute locally with no per-agent branches.
  Rationale: User explicitly requested local execution only.
  Date/Author: 2026-02-24 / Codex

- Decision: Start with modules `app`, `core-domain`, `core-data`, `core-stubs`.
  Rationale: Gives stable contract boundaries early while keeping initial milestone tractable.
  Date/Author: 2026-02-24 / Codex

- Decision: Keep compileSdk at 35 to align with current Android SDK and spec trajectory while suppressing AGP warning for now.
  Rationale: Enables immediate progress; AGP upgrade can be done in a later hardening step.
  Date/Author: 2026-02-24 / Codex

- Decision: Implement milestone persistence with file-backed JSON repositories first, while retaining room for Room/DataStore substitution later.
  Rationale: Provides deterministic local persistence in this phase without blocking on Android-specific infrastructure; preserves repository boundaries for later swap.
  Date/Author: 2026-02-24 / Codex

- Decision: Introduce a shared `SearchCoordinator` in the app module to keep Draft/Applied behavior consistent between Search and Explore before ViewModel/store extraction.
  Rationale: Fastest path to unblock milestone UI behavior while preserving a single query state authority.
  Date/Author: 2026-02-24 / Codex

- Decision: Ship a staged filter bottom-sheet flow in Milestone 5 instead of waiting for Settings integration.
  Rationale: Matches the Search UX contract now and keeps apply-only execution semantics visible to users.
  Date/Author: 2026-02-24 / Codex

- Decision: Add explicit core-data contracts for codex detail hydration/sorting (`CodexSortMode` + `observeCodexPosts/getPost`) and app-level UI restore state (`UiRestoreRepository`).
  Rationale: Enables one-pass implementation of Viewer/Codex flow and deterministic query-hash-based restoration without introducing Android-specific storage dependencies.
  Date/Author: 2026-02-24 / Codex

- Decision: Keep persistence file-backed for this pass (queries, codex metadata/posts, settings, UI restore, cache paths) and defer Room/DataStore migration.
  Rationale: Delivers full MVP behavior now while preserving contract seams for later storage backend swap.
  Date/Author: 2026-02-24 / Codex

- Decision: Use hybrid storage for source auth credentials only (Android Keystore-backed encrypted preferences) while keeping all non-sensitive app state in existing file-backed repositories.
  Rationale: Meets beta security requirements without blocking current persistence architecture.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep runtime in real-only cutover mode and expose only Pixiv in Phase A; keep stubs test/dev-only.
  Rationale: Avoids soft-fail UX for unfinished providers while preserving deterministic stub coverage in tests.
  Date/Author: 2026-02-25 / Codex
- Decision: Use GitHub prereleases as the mobile-resolvable “latest main build” contract with fixed asset name and strict tag parsing (`main-vc<versionCode>-<sha>`).
  Rationale: Mobile clients cannot reliably resolve raw latest commit artifacts; release metadata gives deterministic versioning, channeling, and asset lookup.
  Date/Author: 2026-02-25 / Codex
- Decision: Preserve pending update metadata after installer launch so startup can reuse an already downloaded APK if install is canceled/aborted.
  Rationale: Reduces redundant network usage and improves startup update UX while still validating APK before each install attempt.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Milestones 1 through 8 are complete and validated for functional/spec-critical behavior. Second-pass source cutover is also complete for architecture-critical scope: runtime registry abstraction, real-source module introduction, secure source credential storage, settings account controls, and Pixiv-first source exposure. Startup now includes updater orchestration for `main` prerelease APK delivery with safe fallback to current install. Remaining gaps are now mostly rollout/theming polish and broader source exposure in later phases.

## Context and Orientation

Current structure now includes:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources`

The spec source of truth remains `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/TheoriaSpec.md`.

Definitions used in this repository:

A SourceAdapter is the per-source implementation boundary for search/trending/quick-query operations.

Unified execution means concurrent adapter search plus capability-aware exclusion and weighted result interleave.

Codex is a persistent local collection of saved posts keyed by `PostId`.

## Plan of Work

Milestone 5 introduces Search and Explore user flows, staged apply behavior, status pills, and loading/empty/error states.

Milestone 6 introduces Viewer gestures and Codex detail save/remove behavior.

Milestone 7 introduces Settings behavior and offline guarantees.

Milestone 8 finalizes validation and documentation.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

    ./gradlew tasks --all
    ./gradlew assembleDebug
    ./gradlew :core-domain:test
    ./gradlew :core-sources:test
    ./gradlew testDebugUnitTest
    ./gradlew connectedDebugAndroidTest
    ./gradlew lintDebug

At each stop point, update this ExecPlan `Progress` and `working_list.md` immediately.

## Validation and Acceptance

Milestone-level acceptance is behavioral and test-driven:

Milestone 1 acceptance: project config resolves and debug APK assembles.

Milestone 2 acceptance: domain model utilities have deterministic tests, including query hash stability.

Final MVP acceptance: all behavior in `docs/TheoriaSpec.md` section 11 works and is demonstrable through app interactions plus passing test suite.

## Idempotence and Recovery

These steps are additive and repeatable. Re-running Gradle tasks is safe. If a step fails, fix the immediate configuration/code issue and rerun the same command. Keep schema evolution additive to preserve local development data across iterations.

## Artifacts and Notes

Key artifacts currently produced:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/query/QueryHash.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/test/kotlin/com/theoriacodex/data/repository/InMemoryRepositoriesTest.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/main/kotlin/com/theoriacodex/stubs/JsonStubSourceAdapter.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/main/resources/stubs/`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/orchestration/UnifiedSearchOrchestrator.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerState.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/CodexListScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/codex/SaveToCodexSheet.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/settings/SettingsScreen.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/search/SearchCoordinatorTest.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/viewer/ViewerStateTest.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapterRegistry.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceFailure.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/RealAdapterRegistry.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/SourceCredentialsStores.kt`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/PixivPkceController.kt`

Validation excerpts:

    BUILD SUCCESSFUL in 2m 20s
    46 actionable tasks: 46 executed

    BUILD SUCCESSFUL in 2s
    :core-domain:test

Plan revision note (2026-02-24 23:08Z): Completed one-pass Milestone 5-8 delivery with repository contract extensions (`CodexSortMode`, explicit settings setters, `UiRestoreRepository`), Viewer/Codex/Settings UI integration, and final validation gates.

Plan revision note (2026-02-25 01:42Z): Completed second-pass source cutover with real adapter module introduction (`:core-sources`), registry-driven runtime source visibility, secure source credential storage, and settings account controls while retaining `:core-stubs` for tests/dev.

Plan revision note (2026-02-25 04:36Z): Added startup auto-update delivery path (GitHub prerelease feed + APK validation/installer startup gate + release workflow contract) and retained fallback-to-current-app behavior for all update failures.

## Interfaces and Dependencies

Implemented contracts now live in:

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Codex.kt`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`

Current required interface (already created):

    interface SourceAdapter {
      val sourceKey: SourceKey
      val capabilities: SourceCapabilities
      suspend fun search(query: Query, pageToken: String?): Page<Post>
      suspend fun trendingTags(limit: Int): List<TagSuggestion>
      suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion>
      suspend fun quickQuery(kind: QuickQueryKind): Query
      suspend fun resolvePost(id: PostId): Post?
    }

    data class SourceCapabilities(
      val supportsSortNewest: Boolean,
      val supportsSortPopular: Boolean,
      val supportsSortTop: Boolean,
      val supportsSortRandom: Boolean,
      val supportsExcludeTagsServerSide: Boolean,
      val supportsDateRangeServerSide: Boolean,
      val supportsMinScoreServerSide: Boolean,
      val requiresCredentials: Boolean
    )

    enum class SourceFailureReason {
      AUTH_REQUIRED,
      AUTH_EXPIRED,
      RATE_LIMITED,
      NETWORK,
      PARSE,
      UNKNOWN
    }

    interface SourceAdapterRegistry {
      fun availableSources(): Set<SourceKey>
      fun adapterFor(sourceKey: SourceKey): SourceAdapter?
      fun unifiedOrchestrator(): UnifiedSearchOrchestrator
    }

Key interface additions delivered:

    enum class CodexSortMode { NEWEST_SAVED, OLDEST_SAVED, BY_SOURCE }

    interface CodexRepository {
      fun observeCodex(codexId: String): Flow<Codex?>
      fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>>
      suspend fun getPost(postId: PostId): Post?
    }

    interface SettingsRepository {
      suspend fun setEnabledSources(enabledSources: Set<SourceKey>)
      suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>)
      suspend fun setCacheFullImageOnSave(enabled: Boolean)
      suspend fun setScenarioPreset(preset: ScenarioPreset)
      suspend fun setLastTab(route: String)
    }

    interface UiRestoreRepository {
      suspend fun setLastTab(route: String)
      suspend fun getLastTab(): String?
      suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState)
      suspend fun getSearchScrollState(queryHash: String): SearchScrollState?
      fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?>
      suspend fun setViewerLaunchContext(context: ViewerLaunchContext?)
    }
