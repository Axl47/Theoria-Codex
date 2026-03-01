---
created_at: 2026-02-24T18:16
updated_at: 2026-02-27T07:04
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
  - Gelbooru user ID/API key save/clear, plus API-field paste parsing for `&api_key=<key>&user_id=<id>`
- Runtime source visibility is registry-driven (Phase A exposure is Pixiv + Gelbooru).
- Search autocomplete now uses source adapter autocomplete APIs as you type (with local suggestion-store fallback).
- In Gelbooru source mode, typed include/exclude tags can only be added when they match suggested tags.
- In Unified mode, Gelbooru queries now apply compatibility mapping by resolving each include/exclude tag to the first Gelbooru autocomplete match (fallback: original tag).
- Gelbooru video posts now map to proper video MIME types (`video/mp4`, `video/webm`, etc.), and Viewer now plays source videos directly (while keeping Pixiv ugoira playback/export behavior).
- Stub adapters remain available for tests/dev in `:core-stubs`.

Previously completed MVP slices remain in place:

- Search / For You / Explore / Codex / Settings tabs
- Search result cards now include a heart toggle that stores per-profile liked-tag memory from the post's source tags
- New `For You` tab provides a browsing-first personalized feed generated from liked tags with per-source tag sets in Unified mode (still merged with existing source weights)
- App now supports dynamic local recommendation profiles (minimum 1) so one user can maintain separate recommendation purposes; profiles can be added/removed in Settings and each profile keeps independent heart/recommendation memory
- Recommendation likes are persisted in `files/theoria_codex/likes_store.json` and can be cleared per active profile from Settings
- Codex now auto-creates a system `Likes` board (`system_likes_codex`) and keeps it synced with heart toggles (add on like; remove when no profile still likes that post)
- Codex list now includes a `Search` action that builds a Unified search draft from that codex's per-post unique canonical tags (top weighted tags), applies it, and navigates directly to Search
- Codex tab now uses a board-style tile layout with per-codex icon actions (Search/Rename/Delete) and a dedicated Reorder mode that supports drag-and-drop ordering with persisted order
- Codex delete actions now show confirmation dialogs from both list and detail screens
- Search results now render real source thumbnails in a variable-height staggered grid and show post titles (when available) instead of raw source IDs
- Search input UX now supports Enter-to-add-tag and contextual controls that animate in while actively editing
- Search source mode chips now show source logos for Pixiv and Gelbooru (instead of plain text labels)
- Search controls include an `Animated only` filter for showing just animated media in current results
- Animated media detection is now shared across Search/Viewer app layers (GIF/video/ugoira), reducing source-specific edge-case mismatches
- Search auto-paginates: near the end of loaded results (about 80%), the next source page is fetched automatically
- Bottom navigation is compact icon-only for higher content density
- Outside Viewer, horizontal swipes now switch between top-level tabs (Search/Explore/Codex/Settings)
- Local tag suggestion store now seeds from `app/src/main/assets/tag_store.json` and is persisted to `files/theoria_codex/tag_suggestions.json` to reduce network tag fetches
- Search/Explore trending tags now auto-refresh in the background (TTL-based) while still rendering cached suggestions immediately, so Pixiv/Gelbooru suggestion pools stay fresh without blocking UI
- Search now ingests seen post tags from loaded results (Pixiv + Gelbooru) into the local suggestion store, improving autocomplete and fallback relevance over time
- Viewer tag metadata now supports batched Gelbooru tag-count lookups and caches those counts in the suggestion store, reducing repeated per-tag network fetch latency in Info sheet renders
- Draft/Applied search flow with explicit Apply semantics
- Search/Viewer now support GIF rendering (global Coil animated decoder + MIME-aware source mapping); Search and Viewer both support Pixiv ugoira playback by loading Pixiv metadata + zip frames and animating in-place
- Search result cards now autoplay video posts in-place (muted + looping) with image fallback when a source video preview fails
- All in-app video playback now uses Media3 ExoPlayer with texture-backed `PlayerView` (Viewer + Search + Codex cards), reducing close/navigation linger and replacing the legacy `VideoView` path
- Viewer long-press download now exports Pixiv ugoira content directly to MP4 and saves it to device video storage (with app-storage fallback when MediaStore is unavailable)
- Viewer now uses horizontal post paging + vertical in-post image paging (per-post `X / Y`), supports long-press image download, and search cards show compact source/image-count overlays
- Viewer now prefetches the next three upcoming media items while browsing to reduce perceived load time when swiping forward
- Viewer now also prefetches upcoming video media into local cache files (for faster replay/re-entry) and force-stops active video playback before dismiss to avoid sluggish exit feel
- Viewer video prefetch now deduplicates in-flight downloads, supports cancellation during rapid browsing, and trims old cache files to keep long-scroll sessions stable
- Pixiv ugoira playback now uses an in-memory LRU cache so returning between Search and Viewer reuses decoded animations instead of reloading from network
- Viewer shows a thin playback progress bar under Pixiv ugoira media so loop position is visible while watching animations
- Viewer animated media now uses an interactive timeline scrubber (drag-to-seek + current time/duration) shared across Pixiv ugoira, GIF, and video playback, with a left-side pause/play toggle in the footer
- Viewer zoom gestures now apply to videos the same as images (double-tap toggle, pinch zoom, and pan with pager-lock while zoomed)
- Viewer Info tag cells now show the source tag video count (when available) as subtle low-opacity metadata under each tag label
- Viewer Info header now includes quick actions for `Go to Search`, `Share` (copy post URL), and `Open in browser` for faster handoff/export
- Codex detail now reuses the same Search result card renderer (thumbnails, animated previews, title/tags, overlays) for consistent browsing UI
- Search cards now support long-press actions for `Save to Codex`, `Save to device`, and `Copy tags`; Codex cards support long-press actions for `Remove from Codex`, `Save to device`, and `Copy tags`
- `Save to Codex` sheet now has a board-style UI pass with per-profile board browsing (profile switch cycles locally inside the sheet without changing app active profile) and board cover thumbnails sourced from cached thumbnail artifacts when available
- App branding now uses custom launcher icon resources and a shared splash mark shown during startup loading and Search pre-query empty state
- Tag seeder script now supports direct Pixiv tag-page ingestion via `--pixiv-tags-url` (with HTML fallback via `--pixiv-tags-html`)
- Viewer now prefetches up to three media items on both sides of the current position and can trigger `load next page` while still inside Viewer for live Search sessions (including animated-only Viewer sessions)
- The app now accepts external Pixiv post URLs in `<scheme>://<pixiv-host>/<locale>/artworks/<id>` format (`http` or `https`; hosts: `www.pixiv.com`, `pixiv.com`, `www.pixiv.net`, `pixiv.net`; two-letter locale like `en`, `ja`) and Gelbooru post URLs in `<scheme>://<gelbooru-host>/index.php?page=post&s=view&id=<id>` format (`http` or `https`; hosts: `www.gelbooru.com`, `gelbooru.com`), opening both directly into Viewer after resolving the post in-app
- Orientation is now route-aware: app surfaces are portrait-only, while Viewer supports landscape rotation
- In landscape Viewer, media now renders edge-to-edge (no card/page inset margins), and ugoira progress is drawn inside the media at the bottom edge
- Startup now includes a GitHub Releases updater path for `main` prereleases: checks on launch, downloads fixed asset `theoria-codex-main.apk` when newer prereleases exist, supports both legacy `main-vc<versionCode>-<sha>` tags and semver `v<major>.<minor>.<patch>` tags, validates package/signature/version, and opens Android installer (with unknown-sources handoff + fallback to current app on failure)
- Startup updater now prompts user choice when a new build is detected (`Yes`, `No`, `Remind Later`): `No` skips that release until a newer one, `Remind Later` snoozes that release for 24h, and `Yes` runs the existing startup-blocking install flow; prompt now shows all release changelog sections between the installed version and the target update (newest to oldest)
- After completing an update install, first launch now shows a one-time “What’s new” dialog that includes every release newer than the previously installed version (newest to oldest), with the current installed version labeled `(Current)`, then clears after dismissal
- Settings now includes an `Updates` section with an `Open changelog` action that loads prerelease history and shows all available release notes; release titles use the published release name (for example `v0.1.9`) and the installed build is marked as `(Current)`
- During installer handoff, startup no longer auto-falls back immediately on transient resumes; it waits for confirmed install completion, and offers an explicit `Continue current version` action if the user decides not to finish installing
- File-backed persistence for query/settings/cache/codex/UI restore state
The project now includes a runnable portrait-locked Android MVP with five top-level tabs:

- Search
- For You
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

Debug builds now use `applicationIdSuffix ".debug"` and `versionNameSuffix "-debug"`, so debug and release can be installed side-by-side with separate app storage/data.

Tag store update helper:

    python3 scripts/update_tag_store.py --source PIXIV --input /path/to/tags.txt
    python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-url
    python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-html /path/to/pixiv-tags-page.html

## Implementation Plan Tracking

Execution and tracking files:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
