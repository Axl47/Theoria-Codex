---
created_at: 2026-02-24T18:16
updated_at: 2026-03-05T22:20
---
# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven art browser.

The product spec lives at `./docs/TheoriaSpec.md`.

Recent updates:
- NHentai is now available as a real source in Search and Unified mode, including gallery-page parsing, tag suggestions, fixed browser-style request headers, and gallery deep-link opening via `nhentai.net/g/<id>/` URLs.
- Codex long-press actions now include share/export to `.json` (title + source/post IDs), and Codex tab has an Import action to reconstruct codices from exported files.
- The app now registers `.json` open intents so opening a Codex export file launches Theoria Codex and attempts import automatically.
- Codex import now accepts provider-backed JSON URIs (including chat/file-share `content://` streams) in addition to public-folder files.
- Codex names now auto-deduplicate on create/rename/import by appending numeric suffixes (` 2`, ` 3`, ...).
- `For You` now supports profile-scoped blacklist controls: trash the current recommendation seed to hide it and refresh immediately, then manage/remove blacklisted tag sets from Settings.
- Top-level tab swipe navigation now uses a pager-based host so horizontal drags reveal adjacent tabs while preserving vertical scroll behavior.

- The project includes five top-level tabs:
  - Search
  - For You
  - Explore
  - Codex
  - Settings

## Project Structure

- `app`: Android shell, navigation, UI screens, secure credential storage, PKCE callback handling.
- `core-domain`: immutable domain models, adapter contracts, orchestration/query utilities.
- `core-data`: repository contracts and file-backed/in-memory implementations.
- `core-sources`: real source integrations and runtime `RealAdapterRegistry`.
- `core-stubs`: fixture-backed stub adapters for tests/dev scenarios.
- `docs/execplans`: living execution plans.

## Local Development

From the project root, run:
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
