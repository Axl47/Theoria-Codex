# Cross-Source Video Media Support (Gelbooru-First)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan must be maintained according to `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/PLANS.md`.

## Purpose / Big Picture

After this change, Gelbooru posts that are videos (for example `.mp4` and `.webm`) will be treated as video media instead of broken images, and users will be able to play them directly in the Viewer. The app will also be prepared for future video-capable sources by moving MIME/type inference to shared utilities and by using generic media-kind detection in app rendering logic. A user can verify success by opening a video post from Search or Codex and seeing playable video in Viewer, plus correct animated filtering and download behavior.

## Progress

- [x] (2026-02-26 00:36Z) Read current adapters, search cards, and viewer media pipeline to map integration points.
- [x] (2026-02-26 00:36Z) Refresh `working_list.md` for this task.
- [x] (2026-02-26 00:37Z) Create this ExecPlan before implementation edits.
- [x] (2026-02-26 00:39Z) Implement shared MIME inference for image/video in `core-sources` and adopt it in Gelbooru/AIBooru/Pixiv adapters.
- [x] (2026-02-26 00:40Z) Implement viewer video playback branch with generic media-kind handling (`VideoView` branch + no-image-prefetch for video).
- [x] (2026-02-26 00:40Z) Align animated detection / action labels / prefetch behavior with shared media-kind helpers.
- [x] (2026-02-26 00:41Z) Add tests for video parsing behavior and run validation commands.
- [x] (2026-02-26 00:42Z) Update docs (`README.md` and `AGENTS.md`) with feature additions and file notes.
- [x] (2026-02-26 00:46Z) Apply follow-up playback hardening: booru `Referer`/`User-Agent` headers for Viewer media requests and download requests.
- [x] (2026-02-26 00:50Z) Add in-card video autoplay previews for Search/Codex cards (muted loop with image fallback), then revalidate app tests/build.
- [x] (2026-02-26 01:02Z) Add shared interactive timeline scrubber and enable drag-to-seek for Viewer ugoira/GIF/video playback.
- [ ] Perform manual device QA checklist (developer-run).

## Surprises & Discoveries

- Observation: Search and app-level animated filtering already special-case `video/mp4` and `video/webm`, but source adapters only infer image MIME values.
  Evidence: `core-sources/.../GelbooruSourceAdapter.kt`, `AibooruSourceAdapter.kt`, and `PixivSourceAdapter.kt` infer only image MIME by extension; `app/.../SearchScreen.kt` and `app/.../TheoriaApp.kt` already reference video MIME strings.

- Observation: Viewer currently routes all non-ugoira media through image loading, so video URLs can only fail or show fallback text.
  Evidence: `app/.../ViewerScreen.kt` always builds `ImageRequest` candidates unless Pixiv ugoira.

- Observation: Search cards can attempt to render video URLs as images when no image thumbnail is present, which causes noisy card failures.
  Evidence: Existing preview pick order included `post.full?.url` fallback without filtering media kind; fixed by selecting only non-video card preview refs.

- Observation: Some booru media endpoints can reject in-app playback/downloads without browser-like headers.
  Evidence: User-reported runtime `Could not play video` despite correct MIME typing; follow-up added source-specific `Referer` + `User-Agent` headers in Viewer and app-level download requests.

- Observation: Users expect video behavior parity between Viewer and Search cards; static thumbnails feel incomplete once Viewer supports video playback.
  Evidence: Direct user request after Viewer fix: “The preview in search should also play the videos.”

- Observation: Supporting GIF scrub/seek requires a controllable playback source; passive image decoders don’t expose seek state.
  Evidence: Follow-up requirement to drag playerhead for GIF/Ugoira/video led to a dedicated Viewer GIF playback path plus shared timeline component.

## Decision Log

- Decision: Add shared media MIME helpers in `core-sources` and use them from Gelbooru, AIBooru, and Pixiv adapters.
  Rationale: Prevents duplicate extension tables and makes future source/video support a one-file extension.
  Date/Author: 2026-02-26 / Codex

- Decision: Use Android `VideoView`-based playback inside Viewer for `video/*` media.
  Rationale: Minimal dependency footprint and fast integration in existing Compose screen without introducing a new media stack.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep Search/Codex cards preview-driven (image thumbnail path) and add robust media-kind detection in Viewer and filters.
  Rationale: Cards already use preview images from boorus; main missing behavior is full viewer playback and correct media semantics.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented the full scoped change: source adapters now infer both image and video MIME through a shared utility, Gelbooru video posts map correctly to `video/*`, app-level media-kind detection is centralized, and Viewer now plays `video/*` media via `VideoView` while preserving Pixiv ugoira behavior. Search/app animated filters now use shared helper logic to keep behavior consistent across screens, and viewer prefetch/download action labels are media-aware.

Validation outcome:

- `./gradlew :core-sources:test :app:testDebugUnitTest` passed.
- `./gradlew assembleDebug` passed.
- Follow-up hardening validation: `./gradlew :app:testDebugUnitTest assembleDebug` passed.
- Search-preview follow-up validation: `./gradlew :app:testDebugUnitTest assembleDebug` passed.
- Timeline-scrub follow-up validation: `./gradlew :app:testDebugUnitTest assembleDebug` passed.

Remaining work is manual on-device verification (playback UX across several posts/sources).

## Context and Orientation

Relevant runtime paths:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` defines `Post` and `ImageRef`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt` maps Gelbooru API fields into `Post`.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/core-sources/src/main/kotlin/com/theoriacodex/sources/aibooru/AibooruSourceAdapter.kt` and `.../pixiv/PixivSourceAdapter.kt` contain duplicate MIME inference logic today.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt` renders preview cards and animated-only filtering.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt` handles full-screen media render, prefetch, and media actions.
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt` mirrors animated-filter helper logic for viewer session rebuilding.

The architecture is adapter-driven: sources emit `Post` objects, and app UI decides render behavior from `ImageRef.mime` and URL extensions. This feature will improve adapter MIME correctness and add native video rendering in the existing Viewer composable.

## Plan of Work

First, add a shared MIME utility in `core-sources` that maps known file extensions/URLs to image and video MIME strings. Replace local adapter helper functions with this shared utility so Gelbooru (and future sources) produce correct `video/*` MIME values.

Second, introduce app-level media-kind helper functions (image/video/ugoira inference from MIME and URL), then update Viewer rendering to branch into video playback when current media is video. Keep Pixiv ugoira path unchanged and keep image path as fallback.

Third, adjust viewer prefetch and sheet labels so videos are not prefetched as images and action labels show “Download video” when appropriate. Align animated-only filters with shared media-kind helpers instead of repeated literal checks.

Finally, add/adjust unit tests in `core-sources` to assert video inference through Gelbooru parsing, update docs, run targeted tests + assemble, and record outcomes.

## Concrete Steps

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

1. Add shared MIME helpers:
   - `core-sources/src/main/kotlin/com/theoriacodex/sources/media/MediaMime.kt`

2. Refactor adapters to shared helpers:
   - `core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapter.kt`
   - `core-sources/src/main/kotlin/com/theoriacodex/sources/aibooru/AibooruSourceAdapter.kt`
   - `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt`

3. Add app media-kind helpers and viewer updates:
   - `app/src/main/java/com/theoriacodex/app/media/PostMedia.kt`
   - `app/src/main/java/com/theoriacodex/app/viewer/ViewerScreen.kt`
   - `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`
   - `app/src/main/java/com/theoriacodex/app/ui/TheoriaApp.kt`

4. Add tests:
   - `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`
   - Optional shared MIME helper unit tests under `core-sources/src/test/.../media/`

5. Update docs:
   - `README.md`
   - `AGENTS.md` (only if new surprising file(s) should be listed)

6. Run validation:
   - `./gradlew :core-sources:test :app:testDebugUnitTest`
   - `./gradlew assembleDebug`

## Validation and Acceptance

Acceptance criteria:

- Gelbooru post mapping emits `video/mp4` or `video/webm` when source data/URL indicates video.
- Opening a video post in Viewer shows playable media, not an image-load failure.
- Viewer does not enqueue video URLs through image-prefetch path.
- Search animated-only mode continues to include GIF/video posts using unified media detection.
- Download action labels match media kind (“Download video” for video posts, “Download image” for stills, “Download MP4” for Pixiv ugoira export).
- Tests pass and debug build succeeds.

## Idempotence and Recovery

The edits are additive/refactor-safe and can be repeated by re-running tests. If a rendering branch breaks, recovery is to keep shared MIME inference changes and temporarily gate Viewer video branch behind conservative `video/*` check while preserving previous image fallback path. No data migrations are needed.

## Artifacts and Notes

Key validation transcript excerpts:

- `./gradlew :core-sources:test :app:testDebugUnitTest`
  - `BUILD SUCCESSFUL in 8s`
- `./gradlew assembleDebug`
  - `BUILD SUCCESSFUL in 2s`
- `./gradlew :app:testDebugUnitTest assembleDebug`
  - `BUILD SUCCESSFUL in 5s`
- `./gradlew :app:testDebugUnitTest assembleDebug`
  - `BUILD SUCCESSFUL in 4s`
- `./gradlew :app:testDebugUnitTest assembleDebug`
  - `BUILD SUCCESSFUL in 4s`

## Interfaces and Dependencies

Expected interfaces after implementation:

- In `core-sources` shared media file:
  - `fun inferMimeFromUrl(url: String?): String?`
  - `fun mimeFromFileExt(ext: String?): String?`

- In app media helper file:
  - Media-kind enum and helpers that classify `ImageRef` and `Post`.

- In Viewer:
  - A composable branch that renders video media for `video/*`.

Revision Note (2026-02-26): Initial creation for Gelbooru-first cross-source video support execution.
Revision Note (2026-02-26): Updated progress, discoveries, and outcomes after implementation + validation pass.
Revision Note (2026-02-26): Added follow-up viewer/download header hardening after user-reported playback failure.
Revision Note (2026-02-26): Added Search/Codex card autoplay video previews after user feedback.
Revision Note (2026-02-26): Added interactive timeline scrubber for ugoira/GIF/video playback after user feedback.
