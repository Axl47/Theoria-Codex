---
created_at: 2026-02-24T18:16
updated_at: 2026-02-24T23:08
---
# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven art browser currently in MVP implementation.

The product spec lives at `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/TheoriaSpec.md`.

## Current Implementation Status

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
- Query-hash keyed search scroll restoration
- Stub-backed search results rendering in a 2-column grid with viewer entry handoff
- Unified status pill rendering for source failure/exclusion states
- Explore quick queries and trending-tag handoff into Search draft state

Milestone 6 (Viewer/Codex) is complete:

- Fullscreen Viewer route with:
  - Horizontal swipe between items
  - Pinch/pan zoom behavior and double-tap fit/2x toggle
  - Single-tap chrome toggle with 1.5s auto-hide
  - Swipe-down dismiss
  - Info/actions sheet (save, browser open, tag include/exclude)
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

- `app`: Android application shell, navigation, Search/Explore/Codex/Viewer/Settings UI, app unit tests.
- `core-domain`: immutable domain models and source adapter contracts.
- `core-data`: repository interfaces and data-layer contract surface (file-backed + in-memory implementations).
- `core-stubs`: stub scenario models for fixture-based source simulation.
- `docs/execplans`: living execution plans.

## Local Development

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

    ./gradlew tasks --all
    ./gradlew assembleDebug
    ./gradlew :core-domain:test

For app unit tests:

    ./gradlew testDebugUnitTest

For instrumentation and lint:

    ./gradlew connectedDebugAndroidTest
    ./gradlew lintDebug

Build before running on device/emulator:

    ./gradlew assembleDebug
    ./gradlew installDebug

## Implementation Plan

Execution is tracked in:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
