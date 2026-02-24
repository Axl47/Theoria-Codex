---
created_at: 2026-02-24T18:08
updated_at: 2026-02-24T22:31
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
- [~] (2026-02-24 22:31Z) Milestone 5 started: Search and Explore UI implementation.
- [ ] Milestone 6: implement Viewer, Codex, and save/remove flows.
- [ ] Milestone 7: implement Settings, restore behavior, and offline guarantees.
- [ ] Milestone 8: full validation and docs finalization.

## Surprises & Discoveries

- Observation: Repository initially had no app/build scaffold.
  Evidence: Initial `rg --files` returned only planning/spec markdown files.

- Observation: First Android build triggered automatic installation of Build-Tools 34.
  Evidence: `./gradlew assembleDebug` installed `Android SDK Build-Tools 34.0.0` before compilation.

- Observation: AGP 8.5.2 warns for compileSdk 35 compatibility.
  Evidence: Build output emitted unsupported compileSdk warning; mitigated by setting `android.suppressUnsupportedCompileSdk=35` in `gradle.properties`.

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

## Outcomes & Retrospective

Milestones 1 through 4 are complete and validated. The codebase now has a runnable app shell, verified domain/query primitives, tested local persistence/cache layers (including file-backed implementations), and fixture-driven stub source adapters with unified weighted orchestration. Active implementation has moved to Milestone 5 (Search + Explore UI behavior).

## Context and Orientation

Current structure now includes:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs`

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

Validation excerpts:

    BUILD SUCCESSFUL in 2m 20s
    46 actionable tasks: 46 executed

    BUILD SUCCESSFUL in 2s
    :core-domain:test

Plan revision note (2026-02-24 22:31Z): Updated plan after completing Milestone 3 (file-backed persistence/cache) and Milestone 4 (fixtures + stub adapters + unified orchestration) with passing validation.

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
      suspend fun quickQuery(kind: QuickQueryKind): Query
      suspend fun resolvePost(id: PostId): Post?
    }

Next interface additions in Milestone 5 will expose Search/Explore UI state containers that consume these repository/orchestration contracts.
