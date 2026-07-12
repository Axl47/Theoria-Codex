# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven media browser and collection app. It is built around a small loop: search real sources, open posts in an immersive viewer, save or like what matters, revisit activity through Recents, and use liked/tag history to drive recommendations.

## Current App Shape

The app has five top-level tabs, in bottom-navigation and pager order:

- `Search`: source-specific or Unified search, staged draft/apply behavior, include/exclude terms, source status chips, autocomplete/favorite tag sheets, direct NHentai gallery ID open, and local filters such as `Animated only`, animated duration range, `Hide liked`, and `Hide saved`. Sources with typed taxonomy expose faceted `Tags`, `Artists`, `Characters`, and `Series` scopes, with `Groups`, `Types`, and `Languages` under `More`.
- `Recents`: local activity history for watched posts and applied searches. Watched posts reopen Viewer as a static recent-post stream; search history entries reapply their saved query in Search. Watched/search/all filters have independent clear actions.
- `For You`: recommendation browsing from profile-scoped liked posts and source/tag affinity. Users can blacklist the current recommendation seed and manage blacklisted tag sets in Settings.
- `Codex`: local saved collections. Codices can be created, renamed, reordered, sorted, deleted, downloaded, exported to JSON, imported from JSON, and used as a source-specific tag search launcher.
- `Settings`: recommendation profiles, source enablement and Unified weights, source accounts, cache controls, provider health snapshots, changelog history, and developer scenario presets in debug builds.

## Viewer And Media

Viewer handles still images, videos, GIF-like animated media, animated WebP, Pixiv ugoira, and multi-page posts. Multi-page and mixed-media posts support full-view paging, a two-column Media Overview that preserves the exact media order, type badges without grid autoplay, and a persisted scroll-direction toggle. Animated media supports playback-rate controls, scrub/seek affordances, and repeated double-tap seek feedback. Animated WebP uses Android's native decoder on API 28 and newer plus a bounded API 26/27 fallback; Media Overview requests a separate static first frame.

Hitomi image galleries keep each ordered page as a Viewer item, and Viewer download saves only the selected page. A Hitomi anime record with a playable video exposes exactly one MP4 item—the poster remains preview metadata rather than becoming a second page—and downloading it creates one `.mp4` request with the source headers.

Media policy is shared across Search, Viewer, Codex, Creator Profile, and downloads. Source-aware request headers, progressive image URLs, canonical download URLs, lazy media resolution, and filename rules live in app media helpers instead of being duplicated inside screens.

## Sources

The app's real-source set includes:

- Pixiv
- Gelbooru
- NHentai
- Hitomi
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
- `core-data`: storage-independent repository contracts, shared policy, DataStore-backed Settings/UI restore, and the remaining bounded atomic-file repositories.
- `core-data-android`: Room ownership for Codex membership, versioned post snapshots, and profile Likes, including verified legacy migration.
- `core-sources`: real source integrations, HTTP infrastructure, source helper policy, media MIME helpers, and opt-in live provider health tooling.
- `core-stubs`: fixture-backed source adapters and provider contract tests for deterministic development and CI coverage.
- `baseline-profile`: connected cold-start and top-level-navigation profile generation for the optimized release app.

`TheoriaApplication` creates one asynchronous `TheoriaAppContainer` in `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`. That container owns repositories, source clients, credentials, updates, coordinators, and cross-repository workflows; Compose only resolves the ready container.

Search, Viewer, For You, and Creator each have a navigation-scoped ViewModel that owns immutable UI state, request identity, paging, and effects. Their composables render state and forward typed actions. `TheoriaApp.kt` remains the navigation and Android-effect host, and cross-route access uses weak ViewModel-lifetime handles rather than a second mutable state owner.

## Local Persistence

Runtime state is local-first. The production owners are:

- `databases/theoria_content.db`: Room owns Codices, ordered membership, reusable versioned post snapshots, profile Likes, and cross-boundary transactions.
- `theoria_codex/settings_store_v3.json`: typed DataStore file for source settings, profiles, favorites, blacklists, Viewer settings, health snapshots, and cache preferences.
- `theoria_codex/ui_restore_store_v2.json`: typed DataStore file for the selected tab, Search scroll state, and Viewer launch restoration.
- `theoria_codex/query_store.json`: applied queries and scroll offsets.
- `theoria_codex/recents_store.json`: watched posts, applied searches, and combined activity history.
- `theoria_codex/tag_suggestions.json`: learned/cached tag suggestions seeded from the bundled `tag_store.json` asset.
- `theoria_codex/update_state.json`: startup updater state, ignored/remind-later choices, pending install metadata, and changelog state.
- `theoria_codex/cache/thumbnails` and `theoria_codex/cache/full`: local media cache folders.

Legacy `codex_store.json`, `likes_store.json`, `settings_store.json`, and `ui_restore_store.json` files are one-time migration inputs. Migration verifies schemas, keys, relationships, counts, hashes, and destination state before archiving any source; conflict or drift fails closed. Source credentials and pending Pixiv PKCE sessions use separate bounded AES-GCM envelopes under `noBackupFilesDir`, backed by Android Keystore and excluded from device transfer.

## Deep Links And Imports

The Android manifest handles Pixiv auth callbacks, source post/profile links for supported providers, and JSON file/content URIs for Codex import. Hitomi routing accepts reader links and gallery paths for anime, CG, doujinshi, manga, artist-CG, game-CG, and image-set posts, plus `artist/<slug>-all.html` creator links. Codex export files contain the title plus source/post IDs, so imports reconstruct collections from source-backed post identities instead of copying the whole local cache.

## Releases And Updates

Release builds enable the startup updater. The updater reads GitHub prereleases for the `main` channel, expects the fixed APK asset name `theoria-codex-main.apk`, validates version metadata/signature, and launches Android's package installer. Debug builds disable the updater and use `applicationIdSuffix ".debug"` plus `versionNameSuffix "-debug"`, so debug and release installs have separate app storage.

Shipping releases enable R8 and resource shrinking. Durable Gson field names are explicit and every release assembly verifies the optimizer mapping against the checked contract manifest. Checked-in baseline/startup profiles cover cold start and all five top-level destinations. The separate `releaseAcceptance` variant keeps the same optimizer behavior but uses a debug key and updater-disabled package identity for non-debuggable device acceptance.

Main-channel releases are deliberate, not made for every push to `main`. A release commit updates `versionName`, its matching SemVer-derived `versionCode`, and `release-notes/v<major>.<minor>.<patch>.md`; pushing the matching annotated `v<major>.<minor>.<patch>` tag starts `.github/workflows/main-prerelease.yml`. The workflow verifies those three pieces agree, signs the APK, and publishes the checked-in user-facing notes. The Android code uses `1_500_000_000 + major * 10_000 + minor * 100 + patch`, preserving the updater’s existing ordering contract.

## Documentation And Plans

- `.docs/PLANS.md`: current ExecPlan authoring standard. New plans should be standalone HTML files in `.docs/exec/`.
- `.docs/exec/*.html`: current executable planning documents for active/modern implementation work.
- `.docs/exec/execplans/*.md`: older Markdown ExecPlans. Treat these mostly as decision logs unless intentionally resuming one.

## Local Development

From the project root, run the deterministic test lane:

```sh
./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test
./gradlew :core-data-android:testDebugUnitTest :core-data-android:lintDebug
./gradlew :app:testDebugUnitTest :app:lint :app:compileDebugAndroidTestKotlin
```

Run the maintainability lane:

```sh
npm ci
npm run audit:duplicates
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :app:detektDebug :core-data-android:detektDebug \
  :core-domain:detektMain :core-data:detektMain \
  :core-sources:detektMain :core-stubs:detektMain
./gradlew :koverXmlReport :koverVerify
```

CI holds total line coverage at 55%, requires at least 60% changed-line coverage in the explicitly listed JVM/core modules, and fails closed if an eligible source is missing from the report. Android/Compose behavior is runtime-validated separately so instrumentation-only code is not mislabeled as uncovered JVM code.

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

Android-related pull requests and main pushes run the deterministic suite on API 35 while the app continues to compile and target SDK 37. The scheduled/manual extended workflow also runs API 27 plus the minified release-acceptance cold-start/callback check on API 35. Provider-live instrumentation remains opt-in and is not part of the deterministic device result.

Opt-in live provider health report:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true
```

Target only Hitomi's live provider probes:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true -Ptheoria.liveSources.sources=HITOMI
```

Strict live provider health report:

```sh
./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true -Ptheoria.liveSources.strict=true
```

Opt-in live app source-route smoke:

```sh
./gradlew :app:testDebugUnitTest -Ptheoria.liveSources=true --tests '*LiveSearchCoordinatorRouteTest*'
```

Target only the Hitomi app route and media-header smoke:

```sh
./gradlew :app:testDebugUnitTest -Ptheoria.liveSources=true -Ptheoria.liveSources.sources=HITOMI --tests '*LiveSearchCoordinatorRouteTest*'
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
