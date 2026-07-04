# Rule34 Family Source Integration

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan must be maintained according to `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`.

## Purpose / Big Picture

After this change, Theoria Codex can search and open four additional real sources: `rule34.xxx`, `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com`. Users gain two more image sources and two more video sources in Search and Unified mode, can open supported deep links from those sites into the app, and can play/download direct video posts from the two KVS-backed video sources. `rule34.xxx` is credential-gated because its API now requires a `user_id` and `api_key`.

## Progress

- [x] (2026-03-07 07:42Z) Created this ExecPlan and refreshed `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`.
- [x] (2026-03-07 09:55Z) Extended shared source plumbing (`SourceKey`, runtime defaults, normalization, credential interfaces, stub loader mappings, UI ordering, request-header maps).
- [x] (2026-03-07 10:18Z) Implemented `rule34.xxx` credentials storage/UI and runtime source gating.
- [x] (2026-03-07 10:54Z) Implemented `Rule34XxxSourceAdapter` with authenticated DAPI search/resolve/trending and anonymous autocomplete.
- [x] (2026-03-07 11:20Z) Implemented `Rule34PahealSourceAdapter` with RSS/HTML search, autocomplete, resolve, and best-effort trending.
- [x] (2026-03-07 11:46Z) Implemented shared KVS parsing utilities plus `Rule34VideoSourceAdapter` and `Rule34GenSourceAdapter`.
- [x] (2026-03-07 12:02Z) Wired Android manifest deep links and app URI parsing for all four sites.
- [x] (2026-03-07 12:44Z) Added source logo assets, tests, stub fixtures, viewer-time video resolution, and documentation updates.
- [x] (2026-03-07 13:27Z) Ran validation: `./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test testDebugUnitTest` and `./gradlew assembleDebug`.

## Surprises & Discoveries

- Observation: `rule34.xxx` no longer returns anonymous DAPI post/tag responses and instead returns a missing-auth message.
  Evidence: `https://rule34.xxx/index.php?page=dapi&s=post&q=index&json=1&limit=1` responded with `Missing authentication. Go to api.rule34.xxx for more information`, while `https://api.rule34.xxx/` documents `user_id` and `api_key` requirements.

- Observation: `rule34.paheal.net` does not expose stable JSON endpoints from its public site, but it does expose RSS search feeds, browser-search suggestions, and HTML pages that are parseable server-side.
  Evidence: `/rss/images/genshin_impact/1` returns structured RSS with `<media:thumbnail>`, `<media:content>`, and `atom:link rel="next"`, while `/browser_search/genshin` returns JSON suggestions.

- Observation: `rule34video.com` and `rule34gen.com` are KVS-style sites whose HTML pages embed direct MP4 URLs and tag metadata in inline player config.
  Evidence: video pages include `kt_player` config entries such as `video_url`, `video_alt_url`, `preview_url`, and `video_tags`, and those URLs resolve to CDN MP4 responses.

- Observation: local JVM tests cannot rely on `android.net.Uri` query helpers because they throw mocked-method runtime exceptions outside instrumented/Robolectric environments.
  Evidence: the initial `ExternalPostDeepLinksTest` failed under `:app:testDebugUnitTest` until the parser core was moved to a plain-string/`java.net.URI` path and the Android `Uri` overload became a thin wrapper.

## Decision Log

- Decision: Implement this as one branch with two internal phases rather than shipping image sources first and video sources later in separate requests.
  Rationale: The user explicitly requested the full phased plan be implemented now, but the code will still be structured as two families of adapters so future maintenance remains clear.
  Date/Author: 2026-03-07 / Codex

- Decision: Hide `rule34.xxx` from runtime-exposed sources unless credentials are configured.
  Rationale: This avoids a source that predictably fails every search for anonymous users while still allowing autocomplete and adapter tests to remain additive.
  Date/Author: 2026-03-07 / Codex

- Decision: Use `rule34gen.com` rather than `rule34gen.net`.
  Rationale: `rule34gen.net` did not resolve during implementation research, while `rule34gen.com` is live and exposes searchable KVS pages and playable MP4 URLs.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Implemented the full Rule34 family rollout in one pass. The app now exposes `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com` by default, gates `rule34.xxx` behind stored credentials, resolves all four source families through the real adapter registry, supports direct deep links for their post/video URL shapes, and applies source-specific browser headers for search, viewer playback, and downloads.

The source layer gained a shared Rule34 utility package for booru/KVS parsing, new adapters for all four sites, and Jsoup-backed HTML/XML parsing. The app layer gained centralized source metadata and deep-link parsing helpers, a `rule34.xxx` credential parser/settings flow, new source logos, and viewer-time lazy resolution so tapped `rule34video.com` and `rule34gen.com` results upgrade from preview clips to direct MP4 playback when opened.

Validation completed successfully with:

    ./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test testDebugUnitTest
    ./gradlew assembleDebug

## Context and Orientation

The source contract is defined in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt`. Concrete real-source integrations live in `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources`, and runtime exposure is controlled by `RealAdapterRegistry`. App-level source behavior is spread across `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchCoordinator.kt`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`, and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/AndroidManifest.xml`.

The existing source families show the implementation patterns to follow. Gelbooru and AIBooru are booru-style JSON adapters, NHentai is a public JSON/HTML hybrid adapter, and Pixiv is an authenticated source with app-side credential storage and request-header handling. Stub sources are fixture-backed and live under `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs`.

For this change, the new sources split into two technical families. `rule34.xxx` and `rule34.paheal.net` are image-oriented sites with booru/Shimmie semantics. `rule34video.com` and `rule34gen.com` are KVS-style video portals whose HTML embeds player configuration containing direct MP4 URLs. This matters because the image family can fit the existing post/image model directly, while the video family needs HTML parsing and direct video URL extraction.

## Plan of Work

Start by extending `SourceKey` and every exhaustive source-specific branch that depends on it. This includes runtime defaults in `core-data`, recommendation tag normalization in `core-domain` and app recommendation helpers, source display ordering in `SearchCoordinator`, source header maps in `SearchScreen`, `ViewerScreen`, and download handling in `TheoriaApp`, plus stub folder mapping in `core-stubs`.

Next, extend credentials support for `rule34.xxx`. Add a new credentials data class and store methods in `SourceCredentialsProvider`, then implement encrypted and in-memory storage. Mirror the Gelbooru settings flow in `SettingsScreen` and `TheoriaApp`, but keep the UI limited to `User ID`, `API Key`, `Save`, and `Clear`. Runtime exposure should include `rule34.xxx` only when credentials are currently present.

Then implement the two image adapters in `core-sources`. `Rule34XxxSourceAdapter` should use `api.rule34.xxx` endpoints for search, tags, and resolve, attach `user_id` and `api_key` to authenticated operations, map auth failures to `AUTH_REQUIRED`, and use the public autocomplete endpoint for suggestions. `Rule34PahealSourceAdapter` should use public RSS/HTML endpoints: RSS for search page results and pagination, browser-search JSON for autocomplete, HTML parsing for resolve by post id, and latest/feed parsing for best-effort trending.

After the image family works, add a shared KVS parser utility in `core-sources` that can read result cards, pagination, inline `kt_player` config, and per-page tag/category/model metadata. Use it to implement `Rule34VideoSourceAdapter` and `Rule34GenSourceAdapter`. Both adapters should search HTML pages at `/search/<query>/`, store the next-page URL as the page token, resolve posts from `/video/<id>/...`, pick the highest-quality direct MP4 URL as `full`, use the poster image as `preview`, and keep a single video media entry in `Post.media`.

Once adapters exist, wire runtime exposure in `RealAdapterRegistry` and the app shell. Add source logos, manifest hosts, URI parsing for all supported post/video URL patterns, source request headers, and any new source-specific text/order behavior in Search and Viewer. Add fixtures and tests for each new source, then update `README.md` and `AGENTS.md` with the new capabilities and any surprising new files or patterns introduced.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Update shared source types and defaults.
2. Add `rule34.xxx` credentials storage and settings UI.
3. Implement the four source adapters and shared KVS parsing utility in `:core-sources`.
4. Wire runtime exposure, deep links, headers, logos, and app-specific source branches.
5. Add `:core-stubs` fixtures and all new tests.
6. Run:

       ./gradlew :core-domain:test :core-data:test :core-stubs:test :core-sources:test testDebugUnitTest
       ./gradlew assembleDebug

Expected outcome:

       BUILD SUCCESSFUL

## Validation and Acceptance

Acceptance is satisfied when:

- Search mode shows `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com` as available sources by default.
- `rule34.xxx` appears only after valid credentials are saved in Settings.
- Searching each source returns posts with working page URLs and parsed tags.
- Opening supported external URLs routes into the app and resolves the matching post.
- `rule34video.com` and `rule34gen.com` video posts play through the existing video viewer path and download with the correct headers.
- New adapter, stub, and app tests pass, and the app still assembles in debug mode.

## Idempotence and Recovery

The work is additive. Re-running the Gradle tests or build is safe. If one source parser fails, it should surface a source-level failure without affecting the others. If a site changes its HTML and breaks autocomplete/trending on the best-effort integrations, search and resolve should continue to work where possible and return empty lists rather than throwing parse failures for optional suggestion paths.

## Artifacts and Notes

Implementation notes to preserve:

- `rule34.xxx` public autocomplete is anonymous and should remain separate from authenticated DAPI requests.
- `rule34.paheal.net` RSS pages are the preferred source for list/search results because they expose thumbnail and full-file URLs without needing brittle gallery-page scraping.
- KVS video pages expose both direct MP4 URLs and download links in HTML, so the existing viewer/download infrastructure can reuse them without cookie persistence.

## Interfaces and Dependencies

`/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` will define four new `SourceKey` values:

    RULE34XXX
    RULE34PAHEAL
    RULE34VIDEO
    RULE34GEN

`/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/credentials/SourceCredentialsProvider.kt` will gain:

    data class Rule34XxxCredentials(
        val userId: String,
        val apiKey: String,
    )

    suspend fun getRule34XxxCredentials(): Rule34XxxCredentials?
    suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials)
    suspend fun clearRule34XxxCredentials()

`/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/build.gradle.kts` will add Jsoup for HTML and XML parsing. No browser automation, WebView, or JavaScript execution should be introduced.

Revision Note (2026-03-07): Initial creation for the full Rule34 family integration implementation.
