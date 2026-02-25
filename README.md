---
created_at: 2026-02-24T18:16
updated_at: 2026-02-25T01:37
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
- Bottom navigation is compact icon-only for higher content density
- Local tag suggestion store now seeds from `app/src/main/assets/tag_store.json` and is persisted to `files/theoria_codex/tag_suggestions.json` to reduce network tag fetches
- Draft/Applied search flow with explicit Apply semantics
- Viewer now renders full-size source images (with Pixiv-safe headers) and keeps gesture-driven zoom/pan + Codex save/remove flow
- File-backed persistence for query/settings/cache/codex/UI restore state

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

## Implementation Plan Tracking

Execution and tracking files:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
