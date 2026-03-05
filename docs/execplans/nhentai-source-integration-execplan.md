# Add NHentai Source Integration

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `PLANS.md` at the repository root.

## Purpose / Big Picture

Users can now search NHentai directly in Theoria Codex, include NHentai in Unified mode, open NHentai galleries in the Viewer as multi-page media, and open `nhentai.net/g/<id>/` links into the app. This adds a reliable fourth real source without changing the app’s query/state model.

## Progress

- [x] (2026-03-05 22:02Z) Added `SourceKey.NHENTAI` and updated source-specific normalization/exhaustive logic in recommendation and app layers.
- [x] (2026-03-05 22:08Z) Implemented `NhentaiSourceAdapter` in `:core-sources` with JSON endpoint search, pagination, resolve-by-id, gallery media URL mapping, and fixed browser-style headers.
- [x] (2026-03-05 22:11Z) Added NHentai adapter unit tests and updated `RealAdapterRegistry` wiring/tests.
- [x] (2026-03-05 22:14Z) Exposed NHentai in app runtime registry and added request headers for Search/Viewer/image download paths.
- [x] (2026-03-05 22:16Z) Added NHentai deep-link host support in `AndroidManifest.xml` and app URI parsing (`/g/<id>/`).
- [x] (2026-03-05 22:18Z) Added `:core-stubs` NHentai fixtures and fixture-loader mapping.
- [x] (2026-03-05 22:20Z) Updated README/TheoriaSpec/AGENTS docs for NHentai source support.
- [x] (2026-03-05 22:21Z) Validation completed: `./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test testDebugUnitTest`.

## Surprises & Discoveries

- Observation: Kotlin enum expansion caused several non-obvious compile breaks in source-specific `when` blocks outside adapter code.
  Evidence: compile failed until NHENTAI handling was added in recommendation and header-routing functions.

- Observation: NHentai JSON APIs are stable enough for list/search/detail, but blocked responses may arrive as HTML instead of JSON.
  Evidence: adapter includes explicit non-JSON detection and maps the failure to a recoverable source error.

## Decision Log

- Decision: Use fixed browser-style headers (`User-Agent` + `Referer`) and no user-configurable source auth for this rollout.
  Rationale: keeps UX simple and minimizes settings/storage churn while satisfying the observed reliability baseline.
  Date/Author: 2026-03-05 / Codex

- Decision: Construct gallery media URLs from `media_id` + image type (`j/p/g`) instead of scraping page HTML.
  Rationale: preserves parity with existing community API clients and keeps the implementation fully JSON-driven.
  Date/Author: 2026-03-05 / Codex

- Decision: Add NHentai deep links in the same app-level intent filter family as Pixiv/Gelbooru.
  Rationale: keeps URL-open behavior consistent across real sources and reuses existing resolve/open flow.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

NHentai is integrated end-to-end as a real source with search, tag suggestions, resolve-by-id, and gallery page playback support in Viewer. The source is exposed in app settings and Unified mode with normalized default weights. The implementation stayed additive, and all existing core/app tests passed.

Remaining follow-up (if reliability issues appear in the wild): add optional user-configurable NHentai headers/cookies in Source Accounts.

## Context and Orientation

Relevant implementation paths:

- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` (`SourceKey` enum)
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/RealAdapterRegistry.kt`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` (registry exposure + deep-link resolver + download headers)
- `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` and `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt` (image request headers)
- `core-stubs/src/main/kotlin/com/theoriacodex/stubs/StubFixtureLoader.kt` and `core-stubs/src/main/resources/stubs/nhentai/`

## Plan of Work

The implementation sequence was: add new `SourceKey`; implement adapter and tests; expose registry/source weights; wire source headers and URI parsing in app; add stubs; update docs and run validation.

## Concrete Steps

Commands run from repo root:

    ./gradlew :core-domain:compileKotlin :core-data:compileKotlin :core-stubs:compileKotlin :core-sources:compileKotlin :app:compileDebugKotlin
    ./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test testDebugUnitTest

Expected output:

    BUILD SUCCESSFUL

## Validation and Acceptance

Acceptance criteria satisfied:

- NHentai appears as an exposed source in app runtime source list.
- NHentai adapter returns parsed `Post` pages with multi-image `media` payload.
- NHentai gallery deep links route through app parser into source resolve flow.
- Source and app unit tests remain green.

## Idempotence and Recovery

Changes are additive and safe to re-run. If NHentai blocks requests and returns HTML, the adapter fails with source errors instead of crashing parse flow.

## Artifacts and Notes

Key artifact files:

- `core-sources/src/test/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapterTest.kt`
- `core-stubs/src/main/resources/stubs/nhentai/search_page_1.json`
- `core-stubs/src/main/resources/stubs/nhentai/search_page_2.json`
- `core-stubs/src/main/resources/stubs/nhentai/trending_tags.json`

## Interfaces and Dependencies

No new external dependencies were introduced. The integration uses existing `SourceHttpClient`, Gson parsing, and the established `SourceAdapter` contract.

Plan revision note (2026-03-05 22:21Z): created after implementation to document decisions, validation evidence, and recovery guidance for future contributors.
