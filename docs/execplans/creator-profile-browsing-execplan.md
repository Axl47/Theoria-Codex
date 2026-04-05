---
created_at: 2026-04-04T00:00
updated_at: 2026-04-04T00:00
---
# Creator Profile Browsing

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `PLANS.md` at the repository root.

## Purpose / Big Picture

After this change, a user can long-press a Pixiv or Gelbooru post, tap the creator name, and browse that creator's uploads in a dedicated in-app page without disturbing the current Search tab query. The creator page looks and behaves like the app's existing browsing surfaces, but it has its own route, its own result pagination, and only the local visibility filters `Animated only`, `Hide liked`, and `Hide saved`.

The feature is intentionally additive. Search remains tag-driven, and creator browsing is sourced from dedicated creator metadata and source-specific upload endpoints. A user should be able to return from the creator page and see their Search tab state exactly as it was before opening the profile.

## Progress

- [x] (2026-04-04 00:00Z) Audited current Search, Viewer, Codex, source adapter, persistence, and viewer-session wiring relevant to creator browsing.
- [x] (2026-04-04 00:00Z) Created the ExecPlan and refreshed `working_list.md` for this implementation.
- [x] (2026-04-04 00:00Z) Implemented domain model changes for creator metadata and optional creator-post adapter capability.
- [x] (2026-04-04 00:00Z) Implemented Pixiv and Gelbooru creator metadata parsing plus creator-post fetching.
- [x] (2026-04-04 00:00Z) Extended post persistence to round-trip creator metadata with backward compatibility for older JSON.
- [x] (2026-04-04 00:00Z) Added creator coordinator, creator route, creator screen, and viewer-stream integration.
- [x] (2026-04-04 00:00Z) Added creator action buttons to Search, Codex, and Viewer sheets.
- [x] (2026-04-04 00:00Z) Added unit test coverage and ran Gradle validation commands.
- [x] (2026-04-04 00:00Z) Updated `README.md` and `AGENTS.md` with the shipped feature and surprising files.

## Surprises & Discoveries

- Observation: The long-press sheets in Search and Codex currently show only title / source ID and tags, while the Viewer info sheet shows tags without the title / source ID block.
  Evidence: `SearchScreen.kt`, `CodexDetailScreen.kt`, and `ViewerScreen.kt` each assemble their own bottom-sheet content instead of using a shared action-sheet component.
- Observation: `Post` already carries `authorName`, but not a stable creator identifier or uploads query token.
  Evidence: `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` currently exposes `authorName` only, and current adapters drop creator profile identifiers after parsing.
- Observation: Viewer stream synchronization is centralized in `TheoriaApp.kt` and currently only understands Search and For You as live sources.
  Evidence: `ViewerSession`, the `LaunchedEffect` that merges live results, and the `ViewerStreamSource` switch all omit a creator-specific path today.
- Observation: Older saved posts can still provide a useful creator button fallback because `authorName` was already persisted even when structured creator metadata was not.
  Evidence: `PostRecord` already contained `authorName`, so the new UI can show a creator button label for supported sources and let runtime resolution hydrate the full creator profile on demand.
- Observation: `SearchVisibilityFilters` was reusable for creator pages without any contract changes because it already models only local visibility rules.
  Evidence: The creator page now uses the same `SearchVisibilityFilters` data class and `filterSearchResults(...)` helper while keeping backend creator fetches unfiltered.

## Decision Log

- Decision: Implement creator browsing as a dedicated route rather than by mutating Search tab state.
  Rationale: The user explicitly does not want creator browsing to replace the current tag search flow, and the app already treats full-screen browsing surfaces as independent routes.
  Date/Author: 2026-04-04 / Codex
- Decision: Scope the first rollout to Pixiv and Gelbooru only.
  Rationale: These are the only sources with defined product requirements and source-specific browsing examples in scope for this change.
  Date/Author: 2026-04-04 / Codex
- Decision: Reuse `SearchVisibilityFilters` and `filterSearchResults(...)` for creator-page local filtering.
  Rationale: The desired filter semantics match Search, and reusing the existing model reduces drift between browsing surfaces.
  Date/Author: 2026-04-04 / Codex
- Decision: Allow creator buttons to fall back to `authorName` on supported sources when structured creator metadata is missing.
  Rationale: This preserves discoverability for older persisted posts while still letting runtime `resolvePost(...)` supply the real creator uploads query when the user taps the button.
  Date/Author: 2026-04-04 / Codex

## Outcomes & Retrospective

The feature now ships end-to-end. Pixiv and Gelbooru posts preserve structured creator metadata, creator pages have a dedicated route and their own paging runtime, Search/Codex/Viewer sheets expose creator buttons, and viewer swiping can stay bound to creator-upload streams. Persistence remains backward-compatible because `creatorProfile` is nullable and old `PostRecord` JSON still loads.

Automated validation passed with:

    ./gradlew :core-sources:test :core-data:test :app:testDebugUnitTest
    ./gradlew :app:assembleDebug

## Context and Orientation

The app is an Android Compose application rooted in `app/`, with shared domain contracts in `core-domain/`, file-backed persistence in `core-data/`, real source integrations in `core-sources/`, and stub adapters in `core-stubs/`. Search and Viewer behavior is coordinated in `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`, which wires tabs, routes, source registries, viewer sessions, and repository-backed state together.

The relevant user-facing search grid is rendered by `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`. Codex browsing uses `app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt`. The full-screen viewer is in `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`. All three surfaces currently have post action sheets, but none has a creator-profile affordance.

On the source side, `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt` defines the per-source search contract, and `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` defines the post payload that flows through the whole app. `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt` and `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt` already parse creator display names, but they do not preserve enough information to browse creator uploads later.

Persistence for saved posts and codex items is handled by `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`. This file serializes `Post` into `PostRecord`; it must evolve in a backward-compatible way because existing users may already have persisted JSON without creator fields.

## Plan of Work

First, extend the domain model so a `Post` can carry a nullable `CreatorProfile`, and add an optional adapter capability for creator-upload paging. This must be additive and default-safe so the rest of the app still compiles while the implementation is in progress.

Next, update the Pixiv and Gelbooru adapters to populate creator metadata on ordinary search and resolve flows, and to expose dedicated creator-upload loading. Pixiv should use its user-illustrations endpoint keyed by user id, while Gelbooru should perform a normal post search keyed by the uploader token `user:<owner>`. No tag-search semantics should change.

Then, extend persistence so `CreatorProfile` survives saving to codices or caches without breaking reads of older store files. That work should include regression tests for both round-trip persistence and legacy JSON compatibility.

After the data layer is ready, add a creator-page coordinator and Compose screen in `app/src/main/java/com/theoriacodex/app/creator/`. This slice must own its own pagination and filtering state, add a new creator route to `TheoriaApp.kt`, and integrate with the existing viewer-session machinery by introducing a new `ViewerStreamSource.CREATOR_PROFILE`.

Finally, add creator entry points to the Search, Codex, and Viewer sheets, update docs, and run the Gradle test/build commands listed in this plan. The action sheets should only show a creator button when the source is supported and creator metadata is complete enough to browse.

## Concrete Steps

Work from the repository root: `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`.

The implementation steps are:

1. Edit `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` to define `CreatorProfile` and add `creatorProfile` to `Post`.
2. Edit `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt` to define `CreatorPostsSourceAdapter`.
3. Update Pixiv and Gelbooru adapters plus their tests.
4. Update `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` and persistence tests.
5. Add `app/src/main/java/com/theoriacodex/app/creator/CreatorProfileCoordinator.kt` and `app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt`.
6. Update `TheoriaApp.kt`, `SearchScreen.kt`, `CodexDetailScreen.kt`, and `ViewerScreen.kt` for creator entry points and route / viewer integration.
7. Update docs and run:
   `./gradlew :core-sources:test`
   `./gradlew :core-data:test`
   `./gradlew :app:testDebugUnitTest`
   `./gradlew :app:assembleDebug`

## Validation and Acceptance

Acceptance is satisfied when:

- Long-pressing a Pixiv post in Search, Codex, or Viewer shows a creator button and opens a dedicated creator page.
- Long-pressing a Gelbooru post with an `owner` uploader shows a creator button and opens a dedicated creator page backed by uploader results.
- Search tab tags and applied query state remain unchanged before and after creator browsing.
- Creator-page filter sheet contains only `Animated only`, `Hide liked`, and `Hide saved`.
- Viewer launched from a creator page stays bound to the creator result stream and can request more creator pages.
- Persisted posts with creator metadata survive app restart, while older persisted posts without creator metadata still load successfully.

## Idempotence and Recovery

All file edits are additive or backward-compatible. If a partially completed step compiles but fails tests, re-run the relevant Gradle target after the next fix. Persistence changes must preserve reads of pre-existing JSON, so rollback should only require code reversion; no data migration should mutate user files in place.

## Artifacts and Notes

Key observed implementation anchors before coding:

    Search action sheet title block:
    app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt

    Viewer live-session merge effect:
    app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt

    Post persistence record:
    core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt

## Interfaces and Dependencies

In `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`, define:

    data class CreatorProfile(
        val source: SourceKey,
        val displayName: String,
        val profileId: String? = null,
        val profileUrl: String? = null,
        val uploadsQuery: String? = null,
    )

and extend `Post` with:

    val creatorProfile: CreatorProfile? = null

In `core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`, define:

    interface CreatorPostsSourceAdapter {
        suspend fun searchCreatorPosts(
            creator: CreatorProfile,
            pageToken: String?,
        ): Page<Post>
    }

In `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`, extend `ViewerStreamSource` with:

    CREATOR_PROFILE

Revision note (2026-04-04): Updated this ExecPlan after implementation to record the completed work, the fallback-to-`authorName` decision for legacy posts, and the Gradle validation commands that passed.
