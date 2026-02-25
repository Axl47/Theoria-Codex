---
created_at: 2026-02-24T18:16
updated_at: 2026-02-25T13:10
---
# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven art browser.

The product spec lives at `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/TheoriaSpec.md`.

## Current Implementation Status

The app is now in second-pass source cutover state:

- Runtime uses real-source wiring through `:core-sources` (no runtime stub registry dependency in app).
- Source contract now supports:
  - adapter autocomplete
  - source credential capability metadata
  - typed source failure reasons in unified orchestration statuses
- Settings includes minimal source account controls:
  - Pixiv connect/disconnect (PKCE callback flow)
  - Gelbooru user ID/API key save/clear
- Runtime source visibility is registry-driven (Phase A exposure is Pixiv only).
- Stub adapters remain available for tests/dev in `:core-stubs`.

Previously completed MVP slices remain in place:

- Search / Explore / Codex / Settings tabs
- Search results now render real source thumbnails in a variable-height staggered grid and show post titles (when available) instead of raw source IDs
- Search input UX now supports Enter-to-add-tag and contextual controls that animate in while actively editing
- Search controls include an `Animated only` filter for showing just animated media in current results
- Search auto-paginates: near the end of loaded results (about 80%), the next source page is fetched automatically
- Bottom navigation is compact icon-only for higher content density
- Outside Viewer, horizontal swipes now switch between top-level tabs (Search/Explore/Codex/Settings)
- Local tag suggestion store now seeds from `app/src/main/assets/tag_store.json` and is persisted to `files/theoria_codex/tag_suggestions.json` to reduce network tag fetches
- Draft/Applied search flow with explicit Apply semantics
- Search/Viewer now support GIF rendering (global Coil animated decoder + MIME-aware source mapping); Search and Viewer both support Pixiv ugoira playback by loading Pixiv metadata + zip frames and animating in-place
- Viewer long-press download now exports Pixiv ugoira content directly to MP4 and saves it to device video storage (with app-storage fallback when MediaStore is unavailable)
- Viewer now uses horizontal post paging + vertical in-post image paging (per-post `X / Y`), supports long-press image download, and search cards show compact source/image-count overlays
- Viewer now prefetches the next three upcoming media items while browsing to reduce perceived load time when swiping forward
- Pixiv ugoira playback now uses an in-memory LRU cache so returning between Search and Viewer reuses decoded animations instead of reloading from network
- Viewer shows a thin playback progress bar under Pixiv ugoira media so loop position is visible while watching animations
- Codex detail now reuses the same Search result card renderer (thumbnails, animated previews, title/tags, overlays) for consistent browsing UI
- Search cards now support long-press actions for `Save to Codex`, `Save to device`, and `Copy tags`; Codex cards support long-press actions for `Remove from Codex`, `Save to device`, and `Copy tags`
- App branding now uses custom launcher icon resources and a shared splash mark shown during startup loading and Search pre-query empty state
- Tag seeder script now supports direct Pixiv tag-page ingestion via `--pixiv-tags-url` (with HTML fallback via `--pixiv-tags-html`)
- Viewer now prefetches up to three media items on both sides of the current position and can trigger `load next page` while still inside Viewer for live Search sessions (including animated-only Viewer sessions)
- Orientation is now route-aware: app surfaces are portrait-only, while Viewer supports landscape rotation
- In landscape Viewer, media now renders edge-to-edge (no card/page inset margins), and ugoira progress is drawn inside the media at the bottom edge
- Startup now includes a GitHub Releases updater path for `main` prereleases: checks on launch, downloads fixed asset `theoria-codex-main.apk` when newer `main-vc<versionCode>-<sha>` builds exist, validates package/signature/version, and opens Android installer (with unknown-sources handoff + fallback to current app on failure)
- Startup updater now prompts user choice when a new build is detected (`Yes`, `No`, `Remind Later`): `No` skips that release until a newer one, `Remind Later` snoozes that release for 24h, and `Yes` runs the existing startup-blocking install flow; prompt includes sectioned changelog data parsed from GitHub release notes (`Highlights`, `New`, `Improvements`, `Fixes`, `Known Issues`)
- After completing an update install, first launch now shows a one-time “What’s new” dialog that includes every release newer than the previously installed version (oldest to newest), with the current installed version shown last and labeled `(Current)`, then clears after dismissal
- Settings now includes an `Updates` section with an `Open changelog` action that loads prerelease history and shows all available release notes; release titles use the published release name (for example `v0.1.9`) and the installed build is marked as `(Current)`
- File-backed persistence for query/settings/cache/codex/UI restore state
The project now includes a runnable portrait-locked Android MVP with four top-level tabs:

- Search
- Explore
- Codex
- Settings

Milestone 5 (Search/Explore) is complete for spec-critical behavior:

- Shared Draft/Applied query coordinator backed by file-backed repositories
- Apply-bar query execution model
- Autocomplete suggestion panel with include/exclude actions
- Filter/Sort bottom sheet with sort, date-range presets, and min-score staging
- Filter sheet now includes `Media Types -> Animated only` staging in the same sort/filter workflow
- Query-hash keyed search scroll restoration
- Stub-backed search results rendering in a 2-column grid with viewer entry handoff
- Search now supports progressive paging (`load more`) and near-end auto-fetch
- Animated-only searches now keep fetching next pages when early pages have no animated matches
- Unified status pill rendering for source failure/exclusion states
- Explore quick queries and trending-tag handoff into Search draft state
- Rounded single-line search bar with inline faded hint (`tag or -tag`) and Enter-to-add-tag behavior
- Search focus now clears when interacting outside search input/suggestions

Milestone 6 (Viewer/Codex) is complete:

- Fullscreen Viewer route with:
  - Horizontal swipe between items
  - Pinch/pan zoom behavior and double-tap fit/2x toggle
  - Single-tap chrome toggle with 1.5s auto-hide
  - Swipe-down dismiss
  - Info/actions sheet (save, browser open, tag include/exclude)
  - `Go to Search` now reliably closes Viewer route/session before navigating
- Codex list/detail flows:
  - Create/rename/delete codex
  - Save/remove post actions
  - Detail sort modes: newest/oldest/by-source
  - Empty-state and back navigation flows

Milestone 7 (Settings/Runtime controls) is complete:

- Enabled-source toggles
- Merge weight sliders with automatic normalization (sum=1.0)
- Cache full image on save toggle
- Clear thumbnail/full-image cache actions
- Stub scenario selector (`Normal`, `Partial Failure`, `Empty`, `Slow`) applied live

Core domain contracts from the spec are in place, including:

- Post and query models
- Codex models
- Source adapter interfaces and capability model
- Deterministic `QueryHash` utility (with initial unit tests)
- Draft/Applied query state primitives and source capability exclusion helpers

Core data layer now includes:

- Repository interfaces for Codex, query state, settings, cache, and UI restore behavior
- In-memory and file-backed implementations for persisted local state
- Codex detail hydration contracts (`observeCodexPosts`, `getPost`) and `CodexSortMode`
- Explicit settings mutation APIs (`setEnabledSources`, `setSourceWeights`, `setScenarioPreset`, etc.)
- Unit tests for in-memory repository behavior

Stub-source execution now includes:

- JSON fixture datasets for Pixiv, Gelbooru, and AIBooru with paging/trending/scenarios
- Scenario-aware stub source adapters (`Normal`, `Partial Failure`, `Empty Results`, `Slow Network`)
- Unified capability-aware weighted search orchestrator with tests

## Project Structure

- `app`: Android shell, navigation, UI screens, secure credential storage, PKCE callback handling.
- `core-domain`: immutable domain models, adapter contracts, orchestration/query utilities.
- `core-data`: repository contracts and file-backed/in-memory implementations.
- `core-sources`: real source integrations and runtime `RealAdapterRegistry`.
- `core-stubs`: fixture-backed stub adapters for tests/dev scenarios.
- `docs/execplans`: living execution plans.

## Local Development

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

    ./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test
    ./gradlew testDebugUnitTest
    ./gradlew assembleDebug
    ./gradlew lintDebug

Build before running on device/emulator:

    ./gradlew assembleDebug
    ./gradlew installDebug

Tag store update helper:

    python3 scripts/update_tag_store.py --source PIXIV --input /path/to/tags.txt
    python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-url
    python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-html /path/to/pixiv-tags-page.html

## Implementation Plan Tracking

Execution and tracking files:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
