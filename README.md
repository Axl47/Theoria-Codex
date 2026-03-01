---
created_at: 2026-02-24T18:16
updated_at: 2026-02-27T07:18
---
# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven art browser.

The product spec lives at `./docs/TheoriaSpec.md`.

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
