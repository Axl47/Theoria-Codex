---
created_at: 2026-06-25T08:56:51Z
updated_at: 2026-06-25T08:56:51Z
---
# Recents Tab Replacing Explore

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` updated as implementation proceeds. This plan follows the repository rules in `PLANS.md`.

## Purpose / Big Picture

Theoria Codex should have a `Recents` tab that helps a user return to posts they have recently watched and searches they have intentionally run. Today the second area of the app is `For You`, while `Explore` is a separate top-level tab that mainly offers quick queries and trending tag shortcuts. This change removes `Explore` and its quick-query surface entirely, then places `Recents` in the second bottom-navigation position from left to right: `Search`, `Recents`, `For You`, `Codex`, `Settings`.

After the change, a user can open the app, tap the second bottom navigation icon, and see their recent watched posts. They can filter the screen to watched posts, searches, or a combined activity feed. Tapping a watched post opens Viewer over the recent-post list. Tapping a search entry restores and applies that query in Search. This must work across app restarts because recents are part of the local-first app experience.

## Progress

- [x] (2026-06-25 08:56Z) Drafted initial Recents tab implementation plan from current app structure and user decisions.
- [ ] Confirm remaining UX choices with the user before implementation: default filter, search-history inclusion in v1, and whether watched posts open a single-post session or a full recent-post session.
- [ ] Implement persistent recents data model and repository tests.
- [ ] Record applied searches and watched posts from existing Search and Viewer flows.
- [ ] Replace `Explore` with `Recents` in top-level navigation and remove Explore quick-query UI/code.
- [ ] Build the Recents UI and wire actions back to Search and Viewer.
- [ ] Update `README.md`, `AGENTS.md` if needed, and this ExecPlan with implementation evidence.
- [ ] Run focused Gradle verification and complete manual device acceptance.

## Surprises & Discoveries

- Observation: Top-level tab order is controlled by enum declaration order, not by a separate navigation model.
  Evidence: `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` uses `TopLevelDestination.entries` for `HorizontalPager(pageCount = { TopLevelDestination.entries.size })`, bottom-bar iteration, icon selection, and programmatic tab jumps.

- Observation: `Explore` is currently lightweight and can be removed without preserving a complex coordinator.
  Evidence: `app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt` only calls `SearchCoordinator.applyQuickQuery`, `SearchCoordinator.prepareExploreTagSearch`, and `SearchCoordinator.loadTrendingTags`; no independent repository or route-specific persistence exists for Explore.

- Observation: Search, For You, Creator Profile, and Codex already share post-card and Viewer-launch patterns.
  Evidence: `SearchResultCard` is used from `SearchScreen`, `ForYouScreen`, `CreatorProfileScreen`, and `CodexDetailScreen`, and Viewer launch is coordinated through `ViewerSession` plus `ViewerLaunchContext`.

## Decision Log

- Decision: Remove `Explore` and its quick-query surface completely instead of migrating quick queries into Recents.
  Rationale: The user explicitly confirmed quick-query behavior can be removed. Keeping it would blur the purpose of Recents and preserve a surface the new tab is replacing.
  Date/Author: 2026-06-25 / Codex

- Decision: Plan `Recents` as a hybrid activity tab with watched posts as the default/primary surface and applied searches as a secondary filter.
  Rationale: The user asked for recent posts watched and maybe searched, and accepted the recommendation to treat watched posts as primary while keeping search history available as a separate activity type.
  Date/Author: 2026-06-25 / Codex

- Decision: Store watched posts and applied searches in a dedicated `RecentsRepository` rather than overloading Codex, Search query restore, or UI restore storage.
  Rationale: Codex means intentional saved collections, QueryRepository means current/restorable search state, and UiRestoreRepository means transient navigation state. Recents is durable activity history with its own retention and clearing behavior.
  Date/Author: 2026-06-25 / Codex

- Decision: Tapping a watched post should open Viewer over the recent-post list unless the user chooses otherwise before implementation.
  Rationale: This makes Recents feel like a real browsing surface and lets the user move backward and forward through recent items. It also matches Search/For You surfaces that pass a list of posts into Viewer.
  Date/Author: 2026-06-25 / Codex

## Outcomes & Retrospective

No implementation has started yet. The first outcome of this plan is an agreed, reviewable blueprint for replacing `Explore` with `Recents`, including persistence, navigation, UI behavior, and validation. Fill this section after each implementation milestone with what changed, what passed, and any remaining manual verification.

## Context and Orientation

Theoria Codex is an Android-first Kotlin app with Jetpack Compose UI. The main app shell lives in `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`. Top-level tabs are represented by `TopLevelDestination`, an enum currently declared as `Search`, `ForYou`, `Explore`, `Codex`, and `Settings`. The app's home screen uses `HorizontalPager` to show one page per enum entry, and the bottom navigation bar iterates the same enum entries to render icons.

`Explore` currently lives in `app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt`. It is not a standalone browsing history surface. It offers quick-query cards such as popular/newest/random and a trending-tag grid. These actions mutate `SearchCoordinator` and then navigate back to Search. This plan removes that screen and removes the Explore enum entry rather than preserving the quick-query behavior elsewhere.

The shared immutable post model is `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`. A `Post` contains source identity, preview/full media references, page URL, size, tags, author, creation time, optional title, optional creator profile, and optional duration. Search queries are modeled in `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt` as `Query`, `QueryMode`, `SortMode`, and `DateRange`.

Repository contracts live in `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`. File-backed and in-memory implementations live in `core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` and `core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt`. Existing persistence uses JSON files under the app storage directory created in `TheoriaApp.kt`, currently `File(appContext.filesDir, "theoria_codex")`. Unit tests for repository behavior live in `core-data/src/test/kotlin/com/theoriacodex/data/repository/`.

Viewer launch state uses `ViewerLaunchContext` and `ViewerStreamSource` from `Repositories.kt`, while the current active Viewer session is represented by `ViewerSession` in `app/src/main/java/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt`. The Viewer screen receives a list of posts plus a start index. That shape is important because Recents can open Viewer with a list of recent posts and a selected starting item without inventing a new Viewer mode.

The term "watched" in this plan means the user opened a post in Viewer. It does not initially mean a video reached a playback threshold or that an image remained visible for a minimum duration. This simpler definition gives useful history immediately and avoids adding playback-progress instrumentation in the first version.

The term "applied search" means the user committed a search query by pressing Apply or by an app flow that calls `SearchCoordinator.applyDraft()`. It does not mean every tag typed into a draft field.

## Plan of Work

First, add the durable data model for Recents in `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`. Define `RecentPostEntry`, `RecentSearchEntry`, `RecentActivityEntry`, and `RecentsRepository`. `RecentPostEntry` should include the full `Post`, `viewedAtEpochMs`, `origin: ViewerStreamSource`, and `originQueryHash: String?`. `RecentSearchEntry` should include the `Query`, `searchedAtEpochMs`, and a stable query hash string. `RecentActivityEntry` can be a sealed interface or a simple type used by UI/coordinator code to combine posts and searches by time. `RecentsRepository` should expose flows for watched posts, searches, and combined activity, plus suspend functions to record a watched post, record an applied search, clear watched, clear searches, and clear all.

Second, implement `FileBackedRecentsRepository` in `FileBackedRepositories.kt` and `InMemoryRecentsRepository` in `InMemoryRepositories.kt`. Follow the existing JSON style used by `FileBackedCodexRepository`, `FileBackedQueryRepository`, and `FileBackedLikesRepository`. Reuse the existing private `PostRecord` and `QueryRecord` conversions in `FileBackedRepositories.kt` if the code remains in the same file. Store the file as `recents_store.json` under the same `storageDirectory`. Deduplicate watched posts by `PostId` so the newest view moves an item to the top. Deduplicate searches by query hash so rerunning the same query updates its timestamp instead of creating duplicates. Use conservative caps such as 200 watched posts and 100 searches unless implementation discovers a stronger local convention.

Third, add repository tests. In `core-data/src/test/kotlin/com/theoriacodex/data/repository/InMemoryRepositoriesTest.kt`, cover dedupe, ordering, and clearing. In `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedRepositoriesTest.kt`, cover persistence across repository instances, `Post` round-tripping with media/title/creator/duration data, query round-tripping, caps, and malformed or legacy file tolerance if existing read helpers already support fallback defaults. Run `./gradlew :core-data:test` after this milestone.

Fourth, record applied searches. Add a `RecentsRepository` dependency to `SearchCoordinator` with an in-memory default, or record from `TheoriaApp.kt` immediately after calls to `searchCoordinator.applyDraft()`. Prefer injecting the repository into `SearchCoordinator` if tests can cover it cleanly, because `applyDraft()` is the single semantic point where draft queries become intentional searches. The implementation should not record retry calls, pagination calls, autocomplete, quick tag changes, or search draft edits. Add or update `SearchCoordinatorTest` to prove `applyDraft()` records one search and rerunning the same query updates/dedupes through the repository.

Fifth, record watched posts. In `TheoriaApp.kt`, after `prepareViewerPostsForLaunch(posts, context)` returns and before navigating to `AppRoute.Viewer`, record the selected post at `context.startIndex` into `RecentsRepository`. Do this for Search, For You, Creator Profile, Codex, direct deep links, and direct NHentai ID-open viewer launches. If the selected post is lazily resolved during launch, record the resolved post from `preparedPosts`, not the stale unresolved card. Do not record when the Viewer is merely restored from `ui_restore_store.json` unless the user opens a post again through an explicit tap.

Sixth, replace the top-level tab. Rename or replace `TopLevelDestination.Explore` with `TopLevelDestination.Recents("recents", "Recents")` and place it second in enum order, immediately after Search. The final enum order should be Search, Recents, ForYou, Codex, Settings. Update the bottom navigation icon to a suitable Material icon such as `Icons.Default.History` or `Icons.Default.Schedule`. Update the `HorizontalPager` page `when` block to render `RecentsScreen` for the Recents destination. Remove the Explore page branch, the `ExploreScreen` import, and the `app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt` file if it is no longer referenced. Remove or deprecate Explore-specific `SearchCoordinator` functions only if no other code uses them: `applyQuickQuery`, `prepareExploreTagSearch`, and `addTrendingTag` should be reviewed with `rg` before deletion.

Seventh, implement `app/src/main/java/com/theoriacodex/app/recents/RecentsScreen.kt`. The screen should be dense and app-native rather than a marketing-style page. Use a title row, a segmented control or filter chips for `Watched`, `Searches`, and `All`, and a scrollable content area. Watched posts should reuse `SearchResultCard` where practical so thumbnails, badges, like controls, and long-press affordances feel consistent. Search entries should be compact rows showing mode/source, include/exclude tags, sort, and relative time. Empty states should be direct: for watched, explain that posts appear after opening them in Viewer; for searches, explain that searches appear after pressing Apply in Search. Avoid referring to Explore or quick queries in empty-state copy.

Eighth, wire Recents actions. Tapping a watched post should build a `ViewerLaunchContext` with a query hash such as `recents:watched`, start at the tapped index, use a new `ViewerStreamSource.RECENTS` if needed, and open Viewer with `liveSearchBinding = false`. If adding a new stream source is too invasive, use `ViewerStreamSource.CODEX` behavior as the model for non-live static lists, but name the stream source clearly if it becomes user-visible in logic. Tapping a search row should restore the `Query` into Search, apply it, switch `homeTabRoute` to Search, and scroll the pager to the Search page. If `SearchCoordinator` lacks an explicit "apply this historical query" function, add a focused method such as `applyHistoricalQuery(query: Query)` that sets the draft query, persists it, and executes it through the same path as Apply.

Ninth, update documentation. In `README.md`, add a recent update describing the Recents tab and update the top-level tab list from Search, For You, Explore, Codex, Settings to Search, Recents, For You, Codex, Settings. In `AGENTS.md`, add any surprising new files only if they are not obvious from the codebase. A new `app/src/main/java/com/theoriacodex/app/recents/` package and persistent `recents_store.json` behavior are important enough to mention if implementation adds them. Update this ExecPlan's `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` with the actual implementation details and validation evidence.

## Concrete Steps

Work from the repository root:

    cd /Users/axel/Desktop/Code_Projects/Personal/Theoria\ Codex

Before editing, inspect current references:

    rg -n "Explore|QuickQuery|prepareExploreTagSearch|applyQuickQuery|TopLevelDestination|ViewerStreamSource|SearchCoordinator\\(" app core-data core-domain core-stubs

Implement the repository milestone first, then run:

    ./gradlew :core-data:test

Expected result: Gradle reports `BUILD SUCCESSFUL`, and new tests in `FileBackedRepositoriesTest` and `InMemoryRepositoriesTest` pass.

Implement SearchCoordinator recording and historical-query application next, then run:

    ./gradlew :app:testDebugUnitTest --tests '*SearchCoordinatorTest*'

Expected result: Gradle reports `BUILD SUCCESSFUL`, and tests show that applied searches are recorded only on Apply-style actions, not on retry or pagination.

Implement navigation, Recents UI, and Viewer wiring next, then run:

    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:compileDebugKotlin
    ./gradlew :app:assembleDebug

Before running on a device or emulator, build first:

    ./gradlew assembleDebug
    ./gradlew installDebug

If broad verification is needed after the feature is stable, run:

    ./gradlew test
    ./gradlew lintDebug

If `lintDebug` fails on unrelated existing lint, record the failure in this ExecPlan with the first relevant error lines and run the focused tests above as the feature validation baseline.

## Validation and Acceptance

Automated acceptance:

- `./gradlew :core-data:test` passes with Recents repository persistence tests.
- `./gradlew :app:testDebugUnitTest --tests '*SearchCoordinatorTest*'` passes with search-history recording tests.
- `./gradlew :app:testDebugUnitTest` passes after app UI and flow wiring.
- `./gradlew :app:compileDebugKotlin` passes.
- `./gradlew :app:assembleDebug` passes.

Manual device acceptance:

- The bottom navigation order is Search, Recents, For You, Codex, Settings.
- The old Explore tab is gone, including quick-query cards and trending-tag selection.
- Opening a post from Search, returning, and tapping Recents shows that post at the top of Watched.
- Opening posts from For You, Codex, Creator Profile, direct deep links, and direct NHentai ID-open flows records them in Watched with the newest item first.
- Tapping a watched post from Recents opens Viewer at that post. Swiping or navigating within Viewer uses the recent-post list and does not attempt to load more from Search or For You.
- Pressing Apply in Search adds or updates a search entry. Retrying the same search or loading more does not add duplicate entries.
- Tapping a search entry in Recents switches to Search, applies that query, and shows the corresponding results/loading state.
- Clearing watched history removes watched entries and leaves search entries intact. Clearing search history removes search entries and leaves watched entries intact. Clearing all empties both.
- Closing and reopening the app preserves watched posts and searches.

User checklist to include in the implementation closeout:

- [ ] Bottom nav shows Search, Recents, For You, Codex, Settings in that order.
- [ ] Explore and quick queries are fully gone.
- [ ] Watched posts appear in Recents after opening Viewer.
- [ ] Search entries appear only after pressing Apply in Search.
- [ ] Tapping watched posts opens Viewer correctly.
- [ ] Tapping search entries reruns the historical search.
- [ ] Clear actions work independently for watched and searches.
- [ ] Recents survive app restart.

## Idempotence and Recovery

The repository changes should be additive and safe to rerun. `FileBackedRecentsRepository` should tolerate a missing `recents_store.json` by starting with empty lists, following existing file-backed repository behavior. If JSON parsing fails and existing helpers return defaults, document that behavior in tests; do not crash the app on a malformed recents file.

Deleting Explore should be done only after `rg` confirms no remaining references to `ExploreScreen`, `TopLevelDestination.Explore`, and quick-query-only methods. If compilation fails because an Explore helper is still used by another flow, keep the helper temporarily, rename it away from Explore terminology only when its broader use is clear, and record the decision in this plan.

Navigation route restoration needs care because older installed builds may have persisted `lastSelectedTabRoute = "explore"` in `settings_store.json` or `ui_restore_store.json`. When implementation resolves the last selected tab, if the saved route no longer maps to a `TopLevelDestination`, fall back to Search or Recents rather than crashing. Prefer Search as the conservative fallback because it is the app's primary action surface.

The Recents list is local-only activity history. Do not delete Codex items, saved files, liked posts, credentials, or search query restore state when clearing recents.

## Interfaces and Dependencies

Define these repository-facing types in `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`, adjusting names only if implementation reveals a better local convention:

    data class RecentPostEntry(
        val post: Post,
        val viewedAtEpochMs: Long,
        val origin: ViewerStreamSource,
        val originQueryHash: String?,
    )

    data class RecentSearchEntry(
        val query: Query,
        val queryHash: String,
        val searchedAtEpochMs: Long,
    )

    sealed interface RecentActivityEntry {
        val occurredAtEpochMs: Long

        data class Watched(val entry: RecentPostEntry) : RecentActivityEntry
        data class Search(val entry: RecentSearchEntry) : RecentActivityEntry
    }

    interface RecentsRepository {
        fun observeWatchedPosts(): Flow<List<RecentPostEntry>>
        fun observeSearches(): Flow<List<RecentSearchEntry>>
        fun observeActivity(): Flow<List<RecentActivityEntry>>
        suspend fun recordWatchedPost(post: Post, origin: ViewerStreamSource, originQueryHash: String?)
        suspend fun recordSearch(query: Query, queryHash: String)
        suspend fun clearWatchedPosts()
        suspend fun clearSearches()
        suspend fun clearAll()
    }

If `ViewerStreamSource.RECENTS` is added, update `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`, `ViewerLaunchContextRecord.toDomain()` fallback logic, and all `when (streamSource)` expressions in `TheoriaApp.kt`. For load-more behavior, Recents should behave like Codex: no live source loading.

Use existing Compose and Material dependencies already present in the app. Do not introduce a new UI framework. Use Material icons already available through the project dependencies. If `Icons.Default.History` is unavailable at compile time, use `Icons.Default.Schedule` or another available Material icon and record the adjustment in `Surprises & Discoveries`.

## Artifacts and Notes

Current relevant files before implementation:

    app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt
    app/src/main/java/com/theoriacodex/app/explore/ExploreScreen.kt
    app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt
    app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt
    app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt
    app/src/main/java/com/theoriacodex/app/codex/CodexDetailScreen.kt
    app/src/main/java/com/theoriacodex/app/viewer/ViewerSessionCoordinator.kt
    core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt
    core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt
    core-data/src/main/kotlin/com/theoriacodex/data/repository/InMemoryRepositories.kt
    core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt
    core-domain/src/main/kotlin/com/theoriacodex/domain/model/Query.kt
    README.md
    AGENTS.md

Expected final user-facing behavior summary:

    Search remains the primary query surface.
    Recents replaces Explore in the second tab position.
    For You moves to the third tab position.
    Codex remains fourth.
    Settings remains fifth.
    Explore quick queries are removed completely.

Revision note, 2026-06-25 / Codex: Created the initial plan after the user confirmed Explore quick queries can be removed and accepted the recommended Recents architecture. This plan intentionally stops before implementation so the UX and engineering scope can be reviewed first.
