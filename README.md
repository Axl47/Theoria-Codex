# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven media browser and collection app. It is built around a small loop: search real sources, open posts in an immersive viewer, save or like what matters, revisit activity through Recents, and use liked/tag history to drive recommendations.

## Current App Shape

The app has five top-level tabs, in bottom-navigation and pager order:

- `Search`: source-specific or Unified search, staged draft/apply behavior, include/exclude tags, source status chips, autocomplete/favorite tag sheets, direct NHentai gallery ID open, and local filters such as `Animated only`, animated duration range, `Hide liked`, and `Hide saved`.
- `Recents`: local activity history for watched posts and applied searches. Watched posts reopen Viewer as a static recent-post stream; search history entries reapply their saved query in Search. Watched/search/all filters have independent clear actions.
- `For You`: recommendation browsing from profile-scoped liked posts and source/tag affinity. Users can blacklist the current recommendation seed and manage blacklisted tag sets in Settings.
- `Codex`: local saved collections. Codices can be created, renamed, reordered, sorted, deleted, downloaded, exported to JSON, imported from JSON, and used as a source-specific tag search launcher.
- `Settings`: recommendation profiles, source enablement and Unified weights, source accounts, cache controls, provider health snapshots, changelog history, and developer scenario presets in debug builds.

## Viewer And Media

Viewer handles still images, videos, GIF-like animated media, Pixiv ugoira, and multi-page posts. Multi-page posts support full-view paging, a two-column Gallery mode, and a persisted scroll-direction toggle. Animated media supports playback-rate controls, scrub/seek affordances, and repeated double-tap seek feedback.

Media policy is shared across Search, Viewer, Codex, Creator Profile, and downloads. Source-aware request headers, progressive image URLs, canonical download URLs, lazy media resolution, and filename rules live in app media helpers instead of being duplicated inside screens.

## Sources

The app currently exposes these real sources:

- Pixiv
- Gelbooru
- NHentai
- Iwara
- Rule34 Paheal
- Rule34 Video
- Rule34 Gen
- Rule34.xxx, after `user_id` and `api_key` are saved in Settings

`core-sources` also contains an AIBooru adapter, but the app exposure policy does not currently include AIBooru in the UI. Source exposure is controlled by `app/src/main/java/com/theoriacodex/app/source/SourceMetadata.kt`; adapter construction is centralized in `core-sources/src/main/kotlin/com/theoriacodex/sources/RealAdapterRegistry.kt`.

Credential-gated local and live checks use these environment variables when available:

```sh
THEORIA_PIXIV_ACCESS_TOKEN
THEORIA_GELBOORU_USER_ID
THEORIA_GELBOORU_API_KEY
THEORIA_RULE34XXX_USER_ID
THEORIA_RULE34XXX_API_KEY
```

## Architecture

- `app`: Android Compose shell, top-level navigation, viewer/search/codex/settings screens, source account flows, deep links, update UI, and app-level coordinators.
- `core-domain`: immutable domain models, source adapter contracts, query state, capability gates, unified search orchestration, and recommendation primitives.
- `core-data`: repository contracts plus file-backed and in-memory implementations for Codex, Search state, Recents, Settings, Likes, cache snapshots, and UI restore.
- `core-sources`: real source integrations, HTTP infrastructure, source helper policy, media MIME helpers, and opt-in live provider health tooling.
- `core-stubs`: fixture-backed source adapters and provider contract tests for deterministic development and CI coverage.

`TheoriaApp.kt` remains the main Compose workflow shell. Construction of repositories, source clients, credentials, update services, and coordinators is centralized in `app/src/main/java/com/theoriacodex/app/ui/TheoriaAppGraph.kt`.

## Local Persistence

Runtime state is local-first and stored under the app files directory in `theoria_codex`. Important files and folders include:

- `codex_store.json`: codices, codex items, and saved post records.
- `query_store.json`: applied queries and scroll offsets.
- `recents_store.json`: watched posts, applied searches, and combined activity history.
- `settings_store.json`: source settings, profiles, favorite tags, blacklists, viewer settings, provider health snapshots, and cache preferences.
- `likes_store.json`: profile-scoped liked posts used by For You.
- `ui_restore_store.json`: last selected tab, search scroll state, and viewer launch context.
- `tag_suggestions.json`: learned/cached tag suggestions seeded from the bundled `tag_store.json` asset.
- `update_state.json`: startup updater state, ignored/remind-later choices, pending install metadata, and changelog state.
- `cache/thumbnails` and `cache/full`: local media cache folders.

## Deep Links And Imports

The Android manifest handles Pixiv auth callbacks, source post/profile links for supported providers, and JSON file/content URIs for Codex import. Codex export files contain the title plus source/post IDs, so imports reconstruct collections from source-backed post identities instead of copying the whole local cache.

## Releases And Updates

Release builds enable the startup updater. The updater reads GitHub prereleases for the `main` channel, expects the fixed APK asset name `theoria-codex-main.apk`, validates version metadata/signature, and launches Android's package installer. Debug builds disable the updater and use `applicationIdSuffix ".debug"` plus `versionNameSuffix "-debug"`, so debug and release installs have separate app storage.

Main-channel releases are deliberate, not made for every push to `main`. A release commit updates `versionName`, its matching SemVer-derived `versionCode`, and `release-notes/v<major>.<minor>.<patch>.md`; pushing the matching annotated `v<major>.<minor>.<patch>` tag starts `.github/workflows/main-prerelease.yml`. The workflow verifies those three pieces agree, signs the APK, and publishes the checked-in user-facing notes. The Android code uses `1_500_000_000 + major * 10_000 + minor * 100 + patch`, preserving the updater’s existing ordering contract.

## Documentation And Plans

- `.docs/PLANS.md`: current ExecPlan authoring standard. New plans should be standalone HTML files in `.docs/exec/`.
- `.docs/exec/*.html`: current executable planning documents for active/modern implementation work.
- `.docs/exec/execplans/*.md`: older Markdown ExecPlans. Treat these mostly as decision logs unless intentionally resuming one.

## Local Development

From the project root, run the deterministic test lane:

```sh
./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test
./gradlew :app:testDebugUnitTest
./gradlew lintDebug
```

Build before installing or running on a device/emulator:

```sh
./gradlew assembleDebug
./gradlew installDebug
```

Compile the device-backed Compose smoke test when the app shell changes:

```sh
./gradlew :app:compileDebugAndroidTestKotlin
```

Run it only when an Android target is attached:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Opt-in live provider health report:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true
```

Strict live provider health report:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true -Ptheoria.liveSources.strict=true
```

Opt-in live app source-route smoke:

```sh
./gradlew :app:testDebugUnitTest -Ptheoria.liveSources=true --tests '*LiveSearchCoordinatorRouteTest*'
```

Override provider probe seeds when needed:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true -Ptheoria.providerProbeCases=/absolute/path/to/cases.json
```

Tag store update helper:

```sh
python3 scripts/update_tag_store.py --source PIXIV --input /path/to/tags.txt
python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-url
python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-html /path/to/pixiv-tags-page.html
```
