---
created_at: 2026-02-24T17:48
updated_at: 2026-05-30T20:35
---
# THEORIA CODEX AGENTS DOCUMENT

## ExecPlans

When writing complex features or significant refactors, use an ExecPlan (as described in `PLANS.md` or the repo root `PLANS.md`) from design to implementation.

## Development Details

Whenever new updates are made, this file (`AGENTS.md`) should be updated with any surprising files not apparent from the codebase. Additionally, the `README.md` file should be updated with any new features or changes the app receives that are not simple fixes, so that users can easily see what's new without having to read through the codebase.

## Surprising Files Added

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`: task-orchestrator live checklist used during implementation execution.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/`: real source integration module containing Pixiv/AIBooru/Gelbooru adapters, HTTP transport, and `RealAdapterRegistry` runtime wiring.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/`: secure source credential store (Android Keystore-backed encrypted preferences) plus Pixiv PKCE authorization controller.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/AndroidManifest.xml`: includes network permission, Pixiv auth callback intent-filters for both `theoriacodex://pixiv-auth/callback` and `pixiv://account/login`, Pixiv + Gelbooru + NHentai + Iwara + Rule34-family post deep-link VIEW filters (auto-verify, `http/https` on `www.pixiv.com`, `pixiv.com`, `www.pixiv.net`, `pixiv.net`, `www.gelbooru.com`, `gelbooru.com`, `www.nhentai.net`, `nhentai.net`, `www.iwara.tv`, `iwara.tv`, `rule34.xxx`, `www.rule34.xxx`, `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com`, with in-app route validation for Pixiv `<locale>/artworks/<id>`, Gelbooru `/index.php?page=post&s=view&id=<id>`, NHentai `/g/<id>[/<page>]`, Iwara `/video/<id>[/slug]`, and the existing Rule34 routes), `.json` VIEW intent filters for direct Codex import handoff, `singleTask` launch behavior on `MainActivity` to preserve PKCE in-progress callbacks, and route-driven orientation (portrait app surfaces, landscape-enabled Viewer via Compose activity orientation control).
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/TagSuggestionStore.kt`: local file-backed tag suggestion cache used to avoid repeated source trending-tag calls while still allowing runtime merges.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/assets/tag_store.json` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/scripts/update_tag_store.py`: seeded tag data + maintenance script.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/drawable/pixiv_logo.png`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/gelbooru_logo.svg`, and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/nhentai_logo.svg`: source-logo assets used by Search mode chips and source badges for Pixiv/Gelbooru/NHentai icon labels.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/PixivUgoiraPlayer.kt`: Pixiv ugoira runtime that calls `/v1/ugoira/metadata`, downloads zip frames with authenticated requests, decodes frame sequences, renders animated playback in Compose, and now exports ugoira downloads as MP4 to device storage.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/media/MediaMime.kt`: shared source-layer media MIME inference utility (image + video extension/URL mapping) used by Gelbooru/AIBooru/Pixiv adapters to normalize media typing.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/media/PostMedia.kt`: app-layer media-kind classifier utilities used by Search, Viewer, and app session logic to consistently detect image/video/ugoira/animated posts.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/MediaTimelineBar.kt`: shared interactive timeline/scrubber UI (drag-to-seek with current/duration labels) reused by Viewer animation/video players.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ExoVideoComponents.kt`: internal Media3 ExoPlayer helpers shared by Viewer/Search/Codex video playback (looping player creation, header-aware data sources, and texture `PlayerView` host wiring).
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/layout/player_view_texture.xml`: texture-backed `androidx.media3.ui.PlayerView` layout (`surface_type=texture_view`) used by Compose `AndroidView` hosts to avoid lingering `VideoView/SurfaceView` teardown artifacts.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/update/` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/test/java/com/theoriacodex/app/update/GitHubReleaseFeedClientTest.kt`: startup auto-update module (GitHub prerelease feed parsing, download, APK validation, installer launch orchestration, persisted pending state) plus parser contract tests.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/.github/workflows/main-prerelease.yml`: CI release publisher contract for main-channel updater (release title/tag `v<major>.<minor>.<patch>` plus semver-derived Android `versionCode`, with updater backward-compatibility for legacy `main-vc<versionCode>-<sha>` tags and fixed `theoria-codex-main.apk` asset).
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/for-you-recommendations-execplan.md`: living ExecPlan for the two-profile recommendation system, hearted-tag memory, and `For You` tab rollout.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/dynamic-tag-refresh-and-count-cache-execplan.md`: living ExecPlan for automatic trending-tag refresh, seen-tag ingestion, and Gelbooru batch tag-count caching.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/for-you-blacklist-execplan.md`: living ExecPlan for profile-scoped For You blacklist behavior, feed trash action, and Settings blacklist management.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/top-level-tab-swipe-pager-refactor-execplan.md`: living ExecPlan for replacing fragile NavHost swipe emulation with pager-based top-level interactive tab navigation.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/nhentai-source-integration-execplan.md`: living ExecPlan for NHentai source rollout (adapter, registry exposure, headers, deep links, stubs, and validation).
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/rule34-family-source-integration-execplan.md`: living ExecPlan for the phased Rule34 family rollout covering `rule34.xxx`, `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/creator-profile-browsing-execplan.md`: living ExecPlan for Pixiv/Gelbooru creator metadata, dedicated creator-page browsing, and viewer integration.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/recommend/`: recommendation runtime package containing `ForYouCoordinator`, `ForYouScreen`, and post training-tag extraction helpers.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/creator/`: creator-profile runtime package containing creator browseability helpers, `CreatorProfileCoordinator`, and the dedicated creator uploads screen used by Search/Codex/Viewer action sheets.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/`: source-aware tag affinity and recommendation tag-set generator used by the app `For You` feed.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-data/src/main/kotlin/com/theoriacodex/data/repository/FileBackedRepositories.kt` (`likes_store.json`, `settings_store.json`): local file-backed likes repository with per-profile/per-post tag memory used by recommendation training, plus profile-scoped For You blacklist persistence in settings.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt`: NHentai JSON integration (`/api/v2/galleries`, `/api/v2/search`, `/api/v2/galleries/<id>`) with descriptive app request headers, gallery-page media URL construction, tag suggestion extraction, and markdown mirror fallback for blocked responses.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/main/resources/stubs/nhentai/`: fixture-backed NHentai stub data (`search_page_1.json`, `search_page_2.json`, `trending_tags.json`) used by `:core-stubs:test` and app/unit test scenarios.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/`: shared Rule34 parsing helpers plus the real adapters for `rule34.xxx`, `rule34.paheal.net`, `rule34video.com`, and `rule34gen.com`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/iwara/`: Iwara public JSON integration for videos-first search, creator upload paging, tag autocomplete, best-effort trending aggregation, and lazy post resolution via `/video/{id}`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/source/SourceMetadata.kt` and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/source/ExternalPostDeepLinks.kt`: centralized source display/header metadata and external post URL parsing for supported hosts.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/sourceauth/Rule34XxxCredentialInputParser.kt`: parser for pasted `rule34.xxx` credential strings (`user_id` / `api_key`) used by Settings.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/rule34xxx_logo.svg`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/rule34paheal_logo.svg`, `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/rule34video_logo.svg`, and `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/res/raw/rule34gen_logo.svg`: Rule34 family source-logo assets used by Search chips and source badges.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/tags/PostTagActionSection.kt`: shared interactive tag action section reused by Viewer, Search long-press, and Codex long-press sheets, including include/exclude toggles and source video-count lookups.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/tags/SourceTagNormalization.kt`: shared source-aware tag normalization and dedupe helpers used by Search and settings persistence for favorite tags.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/favorite-tags-execplan.md`: living ExecPlan for profile-scoped, source-specific favorite tags and the Search `List` bottom sheet.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-stubs/src/main/resources/stubs/iwara/`: fixture-backed Iwara stub data (`search_page_1.json`, `search_page_2.json`, `trending_tags.json`) used by source/stub/app test coverage after the enum expansion.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/iwara-source-integration-execplan.md`: living ExecPlan for the videos-first Iwara rollout covering source wiring, lazy resolve, creator uploads, deep links, and validation.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/animated-duration-filter-execplan.md`: living ExecPlan for animated duration filtering across Search, Creator Profile, and For You, plus the Settings toggle for background unknown-duration resolution.

## Final Output

When asking the user to verify implemented changes, output a checklist they can fill to make sure everything works as intended. Describe what they should see, how it should work, and keep in mind the possible checkboxes types (`[x]` is completed, `[~]` is partial, `[ ]` is not completed/not working). The user will then fill in the checklist and provide feedback on any issues they encounter, which can be used to further refine the implementation.

Occasionally, remind the developer of the commands they need to use to test the changes, lest they run the run command and forget to build before then. For example, if they need to run `npm run build` before `npm run start`, remind them of this in the final output instructions.

Include a commit message after each change, following the Conventional Commits specifications. If it's a big change, follow this format:

```txt
feat(update): add startup update prompt choices and sectioned changelog pipeline

- feat(update): gate startup updates behind user choice (Yes/No/Remind Later)
- feat(update): persist per-release prompt decisions (ignore until newer, 24h remind-later)
- refactor(update): split updater flow into eligibility check and install phases
- feat(update): parse GitHub release body into sectioned changelog blocks for in-app prompt
- test(update): add updater decision/state-store/changelog parser coverage
- feat(ci): generate release notes sections from commit metadata and publish via body_path
- feat(ci): support multi-section changelog from Conventional Commit lines in commit body
- fix(navigation): clamp bottom navbar sizing to prevent tiny rendering on some phones
- fix(navigation): make top-level tab swipe detection more reliable in Explore
- fix(search): move Explore apply+navigate to app scope to prevent canceled loads on slower devices
- docs(readme): document updater prompt behavior and changelog contract
```
