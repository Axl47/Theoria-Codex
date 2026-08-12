---
created_at: 2026-06-25T00:00:00Z
updated_at: 2026-06-25T02:00:00Z
---
# Maintainability and Provider Reliability Refactor

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository includes `PLANS.md`; this document is maintained in accordance with it.

## Purpose / Big Picture

Theoria Codex already supports several real content providers, saved Codex posts, media playback, recommendations, and source-specific account state. As the app has grown, repeated media selection logic, repeated provider parsing helpers, and a very large `TheoriaApp.kt` composition root make changes harder to reason about and easier to regress. After this plan is implemented, saved posts should round-trip all media metadata that the app already knows, Search and Viewer should choose and share media consistently, providers should be covered by repeatable contract tests, and users should have clearer visibility into provider health without making normal CI depend on live network calls.

The result should be observable in three ways: Gradle tests prove provider and persistence contracts, manual app use shows no behavior regression in Search, Viewer, Codex, and Settings, and an opt-in provider health command can report which real providers are reachable at the time it is run.

## Progress

- [x] (2026-06-25 00:00Z) Created this ExecPlan from the repository analysis and refreshed `working_list.md`.
- [x] (2026-06-25 00:20Z) Implemented persistence integrity fixes for saved Codex posts; `Post.durationMs`, preview `ImageRef.progressiveUrls`, full-image progressive URLs, and media progressive URLs now round-trip through the file-backed Codex store.
- [x] (2026-06-25 00:42Z) Extracted shared media, clipboard, and download policies used by Search, Viewer, Codex, and Creator Profile; candidate selection now lives in `PostMedia.kt` and post URL/tag clipboard writes live in `PostClipboard.kt`.
- [x] (2026-06-25 01:02Z) Added provider contract tests and shared provider parsing/query helpers; fixture-backed stubs now run a cross-source contract suite, and AIBooru/Gelbooru use common source quick-query, JSON, duration, MIME, network, and HTTP failure helpers.
- [x] (2026-06-25 01:25Z) Added opt-in live provider health reporting and Settings-facing health state; `:core-sources:providerHealthCheck` writes a skipped report by default and performs live checks only with `-Ptheoria.liveProviders=true`.
- [x] (2026-06-25 01:45Z) Split viewer session/lazy-media policy and Codex import/export payload policy out of `TheoriaApp.kt`; added focused tests for viewer merging/lazy resolution and Codex share-file parsing/naming.
- [x] (2026-06-25 02:00Z) Completed final provider-message polish and documentation; Search now uses tested friendly provider failure text for status chips and empty-state banners.

## Surprises & Discoveries

- Observation: `Post` already carries `durationMs` and `ImageRef` already carries `progressiveUrls`, but the file-backed Codex persistence records do not store those fields.
  Evidence: `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` defines the fields, while `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` maps `PostRecord` and `ImageRefRecord` without them.

- Observation: Search and Viewer each choose playable media candidates with locally duplicated rules.
  Evidence: `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` and `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt` both derive media URLs from `Post.images`, `Post.videoUrl`, and source-specific fallbacks.

- Observation: Provider adapters repeat query construction and metadata parsing patterns that should be centralized only after contract tests exist.
  Evidence: AIBooru, Gelbooru, Pixiv, Iwara, and Rule34-family adapters each perform similar quick-query, duration, request, JSON, and error mapping work in separate files under `core-sources/src/main/kotlin/com/theoriacodex/sources/`.

- Observation: Full-image and media progressive URLs were already partially protected, but preview progressive URLs and post duration were still dropped.
  Evidence: `FileBackedRepositoriesTest` already covered `full.progressiveUrls` and `media.progressiveUrls`; the updated round-trip test now also asserts `preview.progressiveUrls` and `durationMs`.

- Observation: Creator Profile copied tags as a single space-separated line while Search and Codex copied comma-separated positive and negative lines.
  Evidence: `CreatorProfileScreen.kt` had a private formatter that joined positives and negatives with spaces; `PostClipboard.kt` now provides one shared formatter covered by `PostMediaTest`.

- Observation: The fixture-backed stub registry already covers every `SourceKey`, making it the safest place to enforce provider contracts without introducing live network flakiness.
  Evidence: `StubAdapterRegistry` builds one `JsonStubSourceAdapter` for every `SourceKey`; `StubProviderContractTest` now checks search identity, media URL presence, tags, paging, autocomplete, quick queries, resolve behavior, and typed partial-failure errors across all sources.

- Observation: Live provider health should treat credential-gated providers as typed failures, not task failures.
  Evidence: `./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true` completed successfully with `ok=6, failed=3`; Pixiv, Gelbooru, and rule34.xxx reported `AUTH_REQUIRED` because no local credentials were supplied.

- Observation: `TheoriaApp.kt` still owns Android side effects, but its viewer and Codex-share decisions were pure enough to extract without changing UI behavior.
  Evidence: `ViewerSessionCoordinator.kt` now owns lazy-media resolution and viewer post merging; `CodexShareModels.kt` now owns share-file construction, import post-id parsing, and export filename sanitization.

- Observation: Search already separated account-required banners from other source failures, so final UX polish could focus on replacing raw enum-style text with clearer user-facing labels.
  Evidence: `SourceFailureUiText.kt` now formats missing account setup, expired sign-in, rate limits, unreachable/blocked providers, parser changes, and unknown failures with unit coverage.

## Decision Log

- Decision: Fix persistence round-trip loss before large UI or adapter refactors.
  Rationale: Losing known `durationMs` or `progressiveUrls` is a concrete correctness issue. It is also a small, testable change that improves reliability before broader code movement.
  Date/Author: 2026-06-25 / Codex

- Decision: Add provider contract tests before centralizing provider helpers.
  Rationale: Source adapters are easy to break subtly because each provider has different response shapes, query syntax, credentials, and rate limits. Contract tests let later refactors prove that search, post lookup, tags, media kind, and failure behavior remain stable.
  Date/Author: 2026-06-25 / Codex

- Decision: Keep live provider health checks opt-in and outside default CI.
  Rationale: Live providers can be down, blocked, rate-limited, or require credentials. Default CI should stay deterministic, while developers still need a command that checks real provider health on demand.
  Date/Author: 2026-06-25 / Codex

- Decision: Defer the `TheoriaApp.kt` split until shared policies and tests exist.
  Rationale: The large composition root controls startup, deeplinks, source accounts, updates, import/export, navigation, and viewer sessions. Moving it first would create a high-risk structural diff without enough behavioral guardrails.
  Date/Author: 2026-06-25 / Codex

## Outcomes & Retrospective

Milestone 1 is complete. Saved Codex posts now retain the duration and progressive preview/full/media URLs already present in the domain model, while legacy JSON files without those fields still load with `durationMs = null` and empty progressive URL lists. Broader milestones remain in progress.

Milestone 2 is complete. Search cards, Viewer gallery/image candidate selection, non-viewer device downloads, and the Search/Codex/Creator/Viewer post URL copy actions now use shared app-layer policy. Creator Profile tag copying intentionally now matches Search and Codex formatting so all post action sheets produce the same clipboard text.

Milestone 3 is complete. Provider contracts now run against deterministic fixture-backed adapters, and the first real adapter family migration moved repeated quick-query construction, JSON parsing, flexible duration parsing, MIME inference, network exception mapping, and HTTP status classification into `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceAdapterCommon.kt`. AIBooru and Gelbooru were migrated first because their existing tests covered the behavior most directly.

Milestone 4 is complete. `core-sources/src/main/kotlin/com/theoriacodex/sources/health/` defines provider health reports and an opt-in CLI; `core-sources/build.gradle.kts` exposes `providerHealthCheck`; settings persistence now stores last-known per-source health snapshots; and Settings displays a compact health line for each source without running live checks from the app.

Milestone 5 is complete as a first structural split of the composition root. `TheoriaApp.kt` delegates viewer lazy media/session merging to `app/src/main/java/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt` and delegates Codex share-file construction, import post-id parsing, and filesystem-safe export naming to `app/src/main/java/com/theoriacodex/app/codex/CodexShareModels.kt`. The app shell still owns Android side effects such as FileProvider, Toasts, repository calls, and navigation.

Milestone 6 is complete. Search provider status chips and empty-state errors now use `app/src/main/java/com/theoriacodex/app/search/SourceFailureUiText.kt` to distinguish disabled/excluded sources, account setup, expired auth, rate limiting, blocked/unreachable providers, parser-response changes, and unknown failures. README, AGENTS, this ExecPlan, and `working_list.md` were updated with the shipped behavior and validation evidence.

## Context and Orientation

The app is a multi-module Android/Kotlin project. `core-domain` contains shared domain models such as `Post` and `ImageRef`. `core-data` contains repositories, including file-backed persistence for saved Codex data and settings. `core-sources` contains real provider adapters for sources such as Pixiv, AIBooru, Gelbooru, NHentai, Iwara, and Rule34-family sites. `core-stubs` contains fixture-backed sources for deterministic tests. `app` contains Compose UI, coordinators, Android integration, source account handling, viewer playback, update prompts, and navigation.

A provider is a content source adapter that can search posts, resolve a post by id, expose tag suggestions, and sometimes fetch creator uploads. A contract test is a test that every provider or provider fixture must satisfy, such as "search returns posts with source ids, stable media metadata, and non-empty URLs" or "blocked credentials become a typed failure instead of a crash." A health check is an opt-in command that performs real network requests against configured providers and reports whether they are currently reachable.

Important files and packages:

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` defines `Post`, `ImageRef`, source identifiers, media URLs, tags, and optional duration metadata.
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` persists saved Codex posts and settings to local JSON files.
- `core-sources/src/main/kotlin/com/theoriacodex/sources/` contains real provider adapters and source-level helpers.
- `core-stubs/src/main/resources/stubs/` contains deterministic fixture responses for provider tests.
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` wires app-level state, repositories, source accounts, deeplinks, updates, imports, exports, navigation, and viewer sessions.
- `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`, `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`, `app/src/main/java/com/theoriacodex/app/creator/`, and `app/src/main/java/com/theoriacodex/app/codex/` contain user-facing surfaces that currently duplicate media, sharing, or tag behavior.
- `app/src/main/java/com/theoriacodex/app/media/PostMedia.kt`, `app/src/main/java/com/theoriacodex/app/viewer/ExoVideoComponents.kt`, and `app/src/main/java/com/theoriacodex/app/viewer/MediaTimelineBar.kt` are existing shared media helpers that should be extended before inventing new media abstractions.

## Plan of Work

Milestone 1 fixes saved-post persistence. Extend the file-backed repository record types so `Post.durationMs` and `ImageRef.progressiveUrls` are written and read. Keep defaults backward-compatible so older JSON files that do not contain these fields still load with `durationMs = null` and an empty progressive URL list. Add tests in `core-data` that save a post containing a video duration and progressive image URLs, reload the repository, and assert that the fields survive unchanged. This milestone is accepted when `./gradlew :core-data:test` passes and the new round-trip test would fail against the current persistence mapping.

Milestone 2 extracts shared media and sharing policy. Add app-layer helpers under `app/src/main/java/com/theoriacodex/app/media/` for choosing display, playback, download, and share candidates from a `Post`. A candidate is a small value object containing the URL, media kind, optional request headers, source id, and why that URL was selected. Move clipboard helpers such as post URL copy and tag formatting into a shared app utility, and keep source-specific behavior explicit through `SourceMetadata` and `ExternalPostDeepLinks`. Replace duplicated logic in Search, Viewer, Codex detail sheets, and Creator Profile one surface at a time. Acceptance requires focused app unit tests for candidate selection and tag formatting, plus manual verification that Search thumbnails, Viewer playback, long-press sheets, and downloads behave the same as before.

Milestone 3 adds provider contract tests before refactoring providers. Define a reusable fixture-backed test suite in `core-sources` or `core-stubs` that every available stub source can run through. The suite should verify search paging, post identity, media URL presence, media kind normalization, tag suggestion shape, creator browsing support where implemented, and typed error behavior for malformed or blocked responses. Once tests are in place, centralize repeated quick-query construction, duration parsing, JSON extraction, request failure mapping, and media MIME inference in source-layer helpers. Refactor one adapter family at a time, running the contract suite after each family. Acceptance requires `./gradlew :core-sources:test :core-stubs:test` to pass after each adapter family migration.

Milestone 4 adds opt-in live provider health. Introduce a provider health model in `core-sources` that reports source id, check name, status, latency, and failure reason. Add a Gradle task or test entry point that only performs live network calls when a property such as `-Ptheoria.liveProviders=true` is supplied. The default test suite must skip live checks. The health command should write a concise JSON report under `build/reports/provider-health/` and print a short terminal summary. In the app, expose persisted last-known health in Settings without blocking normal browsing. Acceptance requires default Gradle tests to remain network-free, while the opt-in command can be run locally to produce a report with per-source statuses.

Milestone 5 splits `TheoriaApp.kt` into smaller coordinators after behavior is protected. Extract dependency creation into an app graph or bootstrap module, source account/auth flows into a source account controller, Codex import/export into a Codex share controller, viewer session state into a viewer coordinator, and startup update prompt orchestration into an update controller. Keep public Compose behavior stable while moving one workflow at a time. Each extraction should leave `TheoriaApp.kt` as the composition surface that connects coordinators to screens, not the place where every workflow is implemented. Acceptance requires `./gradlew :app:testDebugUnitTest :app:assembleDebug` to pass and manual smoke testing of startup, source auth, external post deeplinks, import/export, Search navigation, Viewer opening, and update prompt eligibility.

Milestone 6 performs final UX polish and documentation. Use the health and shared media infrastructure to improve user-facing messages: source disabled state, provider unhealthy state, missing credentials, blocked response, unsupported media, and unknown duration should be distinguishable. Update `README.md` with new user-visible behavior, update `AGENTS.md` for any new surprising files, and update this ExecPlan with validation transcripts and lessons learned. Acceptance requires the manual checklist in this plan to be completed and the final documentation to match the shipped behavior.

## Concrete Steps

Work from `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`.

Start each milestone with a clean status check:

    git status --short

For Milestone 1, inspect the current mappings:

    rg -n "data class PostRecord|data class ImageRefRecord|toRecord|toDomain" core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt
    sed -n '1,120p' core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt

Then update `FileBackedRepositories.kt` and add focused round-trip tests. Run:

    ./gradlew :core-data:test

For Milestone 2, locate duplicated media and clipboard behavior before editing:

    rg -n "progressiveUrls|videoUrl|formatPostTagsForClipboard|copyPostUrlToClipboard|download" app/src/main/java/com/theoriacodex/app

Add shared helpers under `app/src/main/java/com/theoriacodex/app/media/` or another existing app package if a more specific local convention is found. Replace call sites one surface at a time and run:

    ./gradlew :app:testDebugUnitTest

For Milestone 3, inventory adapters and stubs:

    find core-sources/src/main/kotlin/com/theoriacodex/sources -type f | sort
    find core-stubs/src/main/resources/stubs -type f | sort

Add contract tests and refactor adapters only after the tests are proving current behavior. Run:

    ./gradlew :core-sources:test :core-stubs:test

For Milestone 4, add the live health entry point so this command remains the explicit opt-in path:

    ./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true

If the implementation chooses a different Gradle task name, update this ExecPlan with the exact command before considering the milestone complete.

For Milestone 5, split one `TheoriaApp.kt` workflow at a time and run:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

For final verification, run the broader suite:

    ./gradlew test

If unrelated legacy failures appear, record them in `Surprises & Discoveries`, run the narrow tests that cover the changed modules, and do not hide or delete unrelated failures.

## Validation and Acceptance

Persistence acceptance:

- A saved Codex post with `durationMs = 12345` reloads with the same value.
- A saved image with multiple `progressiveUrls` reloads with those URLs in the same order.
- Existing saved files without the new fields still load successfully.

Shared media and UX acceptance:

- Search thumbnails, Viewer playback, Codex detail media, Creator Profile post actions, and downloads agree on the selected best media URL.
- Copying post URLs and copying tag lists produce consistent output from Search, Viewer, Codex, and Creator Profile.
- Unsupported media and missing media show clear user-facing messages instead of silent failure.

Provider contract acceptance:

- Every stub provider returns source-stable post ids, media URLs, tags, and paging behavior under the shared contract test suite.
- Provider-specific differences are encoded as explicit capabilities, not hidden branches inside shared tests.
- Adapter refactors do not change fixture-observed behavior except where the plan records an intentional bug fix.

Provider health acceptance:

- Default `./gradlew test` does not perform live provider network calls.
- The opt-in live health command produces a JSON report with source id, status, latency, and failure reason.
- Settings can show last-known provider health without blocking normal browsing or search.

Composition acceptance:

- `TheoriaApp.kt` becomes shorter and easier to scan because workflows are delegated to named coordinators.
- Startup, update prompts, source auth callbacks, external deeplinks, Codex import/export, Search navigation, and Viewer sessions still work.
- Unit tests or focused coordinator tests cover extracted workflow decisions where possible.

Manual verification checklist:

- [x] Search works in Unified mode and single-source mode. User-accepted 2026-08-12.
- [x] Viewer opens image, video, and animated posts. User-accepted 2026-08-12.
- [x] Codex saved posts preserve duration and progressive image fallback data after app restart. User-accepted 2026-08-12.
- [x] Creator Profile browsing still opens uploads and post action sheets. User-accepted 2026-08-12.
- [x] Settings shows useful source/provider status without blocking the rest of the app. User-accepted 2026-08-12.
- [x] External post deeplinks still route to the intended post. User-accepted 2026-08-12.
- [x] Importing and exporting Codex data still works. User-accepted 2026-08-12.

## Idempotence and Recovery

Each milestone should be implemented as a small, reviewable change. Persistence additions must be backward-compatible because users may already have local JSON files without the new fields. Provider contract tests should run against fixtures and should not mutate real provider state. Live provider health checks must be opt-in because network conditions and provider availability are outside the repository's control.

If an adapter refactor changes fixture output unexpectedly, stop and decide whether the fixture exposed a real existing bug or the refactor introduced a regression. Record the decision in `Decision Log`. If the `TheoriaApp.kt` split becomes noisy, pause after the current workflow extraction, run tests, and update this plan with the new boundary before continuing.

## Artifacts and Notes

Initial baseline from the analysis that produced this plan:

- `./gradlew test` passed before implementation work began.
- The main correctness issue identified was persistence loss for `Post.durationMs` and `ImageRef.progressiveUrls`.
- The main maintainability issue identified was duplicated media, clipboard, query, parsing, and workflow orchestration code.
- The main reliability gap identified was lack of shared provider contract tests and opt-in live provider health reporting.

Update this section with concise terminal transcripts after each milestone. Keep examples short and focused on evidence, such as:

    ./gradlew :core-data:test
    BUILD SUCCESSFUL in 18s

    ./gradlew :app:testDebugUnitTest
    BUILD SUCCESSFUL in 17s

    ./gradlew :core-sources:test :core-stubs:test
    BUILD SUCCESSFUL in 4s

    ./gradlew :core-sources:test :core-data:test :app:testDebugUnitTest :core-sources:providerHealthCheck
    Provider health skipped. Re-run with -Ptheoria.liveProviders=true to perform live checks.
    BUILD SUCCESSFUL in 12s

    ./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true
    Provider health: ok=6, degraded=0, failed=3, skipped=0
    BUILD SUCCESSFUL in 5s

    ./gradlew :app:testDebugUnitTest
    BUILD SUCCESSFUL in 6s

    ./gradlew :app:testDebugUnitTest
    BUILD SUCCESSFUL in 4s

    ./gradlew test
    BUILD SUCCESSFUL in 4s

## Interfaces and Dependencies

Expected interfaces after implementation should include:

- A file-backed persistence representation that includes `Post.durationMs` and `ImageRef.progressiveUrls` with backward-compatible defaults.
- An app-layer media selection helper under `app/src/main/java/com/theoriacodex/app/media/` that returns typed media candidates for display, playback, download, and sharing.
- A shared clipboard/tag formatting helper used by Search, Viewer, Codex, and Creator Profile.
- Source-layer helpers for quick-query construction, duration parsing, JSON extraction, request failure mapping, and media MIME inference.
- Provider contract tests that run against fixture-backed providers without network access.
- An opt-in provider health check command that emits a JSON report and never runs by default in CI.
- Smaller app workflow coordinators for source account/auth handling, Codex import/export, viewer session state, update prompts, and dependency bootstrap.

Revision note, 2026-06-25 / Codex: Initial plan created from the maintainability and provider reliability analysis so future implementation can proceed in independently verifiable milestones.
