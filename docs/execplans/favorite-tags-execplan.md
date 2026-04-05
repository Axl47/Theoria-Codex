# Favorite Tags and Search List Sheet

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This repository includes `PLANS.md`; this document is maintained in accordance with it.

## Purpose / Big Picture

After this change, a user can long-press a tag from the shared post tag menu in Viewer, Search, or Codex to save that tag as a profile-scoped favorite for that source. In Search, a new `List` button beside `Add` opens a sheet of saved favorite tags so the user can quickly add favorite tags into the current search without retyping them, or remove favorites they no longer want.

## Progress

- [x] (2026-04-04 14:35Z) Captured the approved Favorite Tags feature plan in an ExecPlan and refreshed the live `working_list.md`.
- [x] (2026-04-04 14:46Z) Added profile-scoped favorite-tag settings contracts, file-backed/in-memory persistence, and shared source-aware normalization in `core-domain`/`core-data`.
- [x] (2026-04-04 14:57Z) Wired long-press favorite-tag creation through the shared tag action UI and app callbacks for Viewer, Search, and Codex.
- [x] (2026-04-04 15:02Z) Added the Search `List` sheet for favorite tags, grouped by source in Unified mode and scoped to one source in source mode.
- [x] (2026-04-04 15:07Z) Added repository/Search unit tests, updated docs, and validated with `:core-data:test`, `:app:testDebugUnitTest`, and `:app:assembleDebug`.

## Surprises & Discoveries

- Observation: The repository documentation still references `working_list.md`, but the file was no longer present in the tree.
  Evidence: `rg --files -g 'working_list.md'` returned no matches before this task.

- Observation: `combinedClickable` in the shared tag grid requires explicit opt-in in this module.
  Evidence: `:app:compileDebugKotlin` initially failed on `PostTagActionSection.kt` until `@OptIn(ExperimentalFoundationApi::class)` was added to the tag-cell composable.

## Decision Log

- Decision: Store favorite tags in `AppSettings` instead of the source-scoped `TagSuggestionStore`.
  Rationale: Favorite tags are profile-scoped state and already align with the settings repository’s persistence and profile-cleanup behavior.
  Date/Author: 2026-04-04 / Codex

- Decision: Move source-aware tag normalization below the `app` module so Search and settings persistence can share one canonical dedupe rule.
  Rationale: File-backed and in-memory settings repositories need the same source-specific keying logic that Search already uses.
  Date/Author: 2026-04-04 / Codex

- Decision: Reuse the existing Viewer-style tag action grid surfaces and pill buttons for the Search favorite-tag sheet instead of introducing a new visual pattern.
  Rationale: This keeps long-press tag menus and favorite-tag management visually consistent while minimizing duplicated Compose code.
  Date/Author: 2026-04-04 / Codex

## Outcomes & Retrospective

Implemented profile-scoped, source-specific favorite tags end to end. Long-pressing a tag label in the shared Viewer/Search/Codex tag menu now saves that tag as a favorite for the active recommendation profile and source, with duplicate-safe persistence and toast feedback. Search now exposes a `List` button beside `Add` that opens a favorite-tag sheet, scoped to the current source or grouped by source in Unified mode, with `Add` and `Remove` actions that keep the sheet open.

Validation completed successfully with:

- `./gradlew :core-data:test`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`

## Context and Orientation

The existing shared post-tag interaction UI lives in `app/src/main/java/com/theoriacodex/app/tags/PostTagActionSection.kt`. It renders the Viewer-style tag action grid used by Search, Viewer, Codex, and creator-profile post menus. Search screen UI lives in `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`, while app-level state wiring lives in `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`. Settings contracts are declared in `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`, with implementations in `core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt` and `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt`.

The feature requires profile-scoped and source-specific favorite tags. “Profile-scoped” means the favorites belong to the active recommendation profile rather than the whole app. “Source-specific” means a saved `blue hair` tag for Pixiv is stored independently from a saved `blue_hair` tag for Gelbooru, and duplicate detection follows the source’s existing search normalization rules.

## Plan of Work

First, extend settings contracts with a `FavoriteTagEntry` model and persistence APIs for adding and removing favorite tags by profile and source. Add a shared source-aware normalization helper in a lower module so repository normalization and Search use the same canonical source tag key. Next, thread a new favorite-tag long-press callback through the shared tag-action grid so Viewer, Search, and Codex can save favorite tags without duplicating UI code.

After persistence and long-press creation are wired, add the Search `List` button beside `Add` and implement a dedicated bottom sheet that shows favorite tags. In source mode the sheet shows only that source’s favorites. In Unified mode it groups favorites by source using Search’s source order. Each favorite tag cell should reuse the same visual language as the Viewer tag action grid and expose `Add` and `Remove` actions without dismissing the sheet.

Finally, add repository tests for persistence, dedupe, and profile cleanup; add app tests for Search favorite-tag grouping and mutations; update `README.md` and `AGENTS.md`; and run Gradle validation.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Update settings contracts and shared tag-normalization utilities.
2. Implement favorite-tag persistence in file-backed and in-memory settings repositories.
3. Wire the shared tag menu long-press favorite action into Search, Viewer, and Codex.
4. Add Search favorite-tag list sheet UI and app-level callbacks.
5. Run:
   - `./gradlew :core-data:test`
   - `./gradlew :app:testDebugUnitTest`
   - `./gradlew :app:assembleDebug`

## Validation and Acceptance

- Long-pressing a tag label in Viewer, Search, or Codex adds it to favorite tags for that source and active profile, with toast feedback.
- Search shows a `List` button to the left of `Add`.
- In source mode, the `List` sheet shows only favorite tags for the current source.
- In Unified mode, the `List` sheet groups favorite tags by source and omits empty sections.
- Tapping `Add` from the favorite-tag sheet adds that tag to Search include-tags without dismissing the sheet.
- Tapping `Remove` deletes the favorite tag without dismissing the sheet.
- Favorite tags switch with the active profile and are removed when a profile is deleted.

## Idempotence and Recovery

Favorite-tag add/remove operations are designed to be idempotent. Repeating an add for the same profile/source/tag should not create duplicates, and repeating a remove for a missing favorite should be a no-op. Settings normalization should recover from malformed persisted data by dropping invalid entries and preserving valid ones.

## Artifacts and Notes

Validation transcript summary:

- `:core-data:test` passed after adding favorite-tag persistence/dedupe/profile-cleanup coverage.
- `:app:testDebugUnitTest` passed after adding Search favorite-tag section ordering/filtering coverage.
- `:app:assembleDebug` passed, confirming the APK still builds after the new Search/Codex/Viewer wiring.

## Interfaces and Dependencies

Expected interfaces after implementation:

- `FavoriteTagEntry` in `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`.
- `AppSettings.favoriteTagsByProfile: Map<String, List<FavoriteTagEntry>>`.
- `SettingsRepository.addFavoriteTag(profileId, source, tag): Boolean`.
- `SettingsRepository.removeFavoriteTag(profileId, source, tag): Boolean`.
- Shared source-tag normalization helpers in a lower module that both settings persistence and `SearchCoordinator` can use.
- `PostTagActionSection(..., onFavoriteTagLongPress = ...)` so long-pressing a tag label can create a favorite.
