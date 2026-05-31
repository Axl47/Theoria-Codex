---
created_at: 2026-05-30T20:14
updated_at: 2026-05-30T20:41
---
# Animated Duration Filter

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows the repository-root `PLANS.md` instructions.

## Purpose / Big Picture

Theoria Codex already lets people narrow feeds to animated media. After this change, people can further constrain animated results by duration with a two-handle range slider in Search, Creator Profile, and For You. The slider keeps static images visible when the user has not chosen `Animated only`, so the duration filter only affects animated content. Unknown animated durations are hidden by default, with an opt-in Settings switch to resolve unknown durations in the background.

## Progress

- [x] (2026-05-31 00:13Z) Created this ExecPlan and refreshed `working_list.md`.
- [x] (2026-05-31 00:27Z) Added `durationMs` to `Post` and persisted `ContentFilterSettings.resolveUnknownAnimatedDurations`.
- [x] (2026-05-31 00:27Z) Added animated duration bucket helpers, strict filtering, and shared slider UI for Search, Creator Profile, and For You.
- [x] (2026-05-31 00:27Z) Added opt-in background resolution hooks for unknown animated durations in all three feeds and viewer live binding.
- [x] (2026-05-31 00:29Z) Parsed known durations from Iwara, Rule34Video, Rule34Gen, Pixiv ugoira resolution, and Iwara stubs.
- [x] (2026-05-31 00:33Z) Added focused tests for filtering, bucket mapping, settings persistence, and adapter parsing.
- [x] (2026-05-31 00:41Z) Ran `./gradlew :app:testDebugUnitTest :core-data:test :core-sources:test :core-stubs:test` successfully after the final UI message adjustment.
- [x] (2026-05-31 00:35Z) Updated README and AGENTS documentation.
- [x] (2026-05-31 00:41Z) Ran `./gradlew :app:assembleDebug` successfully after the final UI message adjustment.

## Surprises & Discoveries

- Observation: `Post` currently has no duration metadata. Viewer components measure playback durations locally, but that information is not available to feed filtering.
  Evidence: `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt` has no duration field; `ViewerScreen.kt` and `PixivUgoiraPlayer.kt` keep duration in Compose state.
- Observation: Search and Creator Profile already share `SearchVisibilityFilters`; For You has separate local `animatedOnly` state.
  Evidence: `SearchScreen.kt` defines `SearchVisibilityFilters`, while `ForYouScreen.kt` filters directly with `coordinator.results.filter(::isAnimatedPost)`.
- Observation: `RangeSlider` compiled under the existing Compose Material3 BOM without requiring new dependencies.
  Evidence: `./gradlew :app:testDebugUnitTest :core-data:test :core-sources:test :core-stubs:test` completed successfully after adding `AnimatedDurationRangeControl`.

## Decision Log

- Decision: Store duration as nullable milliseconds on `Post`.
  Rationale: This keeps adapter output source-neutral, preserves existing constructors with a default value, and avoids adding media-player probing to list filtering.
  Date/Author: 2026-05-31 / Codex.
- Decision: Treat full slider range as inactive.
  Rationale: This avoids hiding unknown animated durations until the user deliberately narrows the duration filter.
  Date/Author: 2026-05-31 / Codex.
- Decision: Make unknown-duration background resolution a Settings switch defaulting off.
  Rationale: Strict default behavior is predictable and avoids extra network work unless the user opts in.
  Date/Author: 2026-05-31 / Codex.

## Outcomes & Retrospective

The feature is implemented and unit-tested. Search, Creator Profile, and For You now share the same animated duration range behavior. Settings persists the background unknown-duration resolution toggle, and source adapters populate durations where reliable metadata is available.

The debug APK build also succeeds.

## Context and Orientation

The shared domain post model is `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Post.kt`. Source adapters in `core-sources/src/main/kotlin/com/theoriacodex/sources/` produce `Post` instances from Pixiv, Iwara, Rule34, and other sources. Search UI and filter behavior live in `app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt`. Creator profile browsing lives in `app/src/main/java/com/theoriacodex/app/creator/CreatorProfileScreen.kt` and `CreatorProfileCoordinator.kt`. For You recommendations live in `app/src/main/java/com/theoriacodex/app/recommend/ForYouScreen.kt` and `ForYouCoordinator.kt`. Settings persistence lives in `core-data/src/main/kotlin/com/theoriacodex/data/repository/`.

An "animated post" means a post for which `app/src/main/java/com/theoriacodex/app/media/PostMedia.kt::isAnimatedPost` returns true. A "duration bucket" is one discrete slider step. Bucket `0` means less than 5 seconds, buckets `1..24` represent 5 through 120 seconds in 5-second increments, and bucket `25` means longer than 2 minutes.

## Plan of Work

First, add `durationMs` to `Post` and add a persisted `ContentFilterSettings` model with a `resolveUnknownAnimatedDurations` switch. Then add app-layer helpers for `AnimatedDurationRange`, bucket labels, and `filterSearchResults` behavior. Next, wire a `RangeSlider` into Search, Creator Profile, and For You. After UI filtering works, add background resolution paths through the existing coordinators so unresolved animated posts can be resolved only when the setting is enabled. Finally, parse known durations in Iwara, Rule34Video, Rule34Gen, Pixiv ugoira resolution, and stub fixtures, then update tests and documentation.

## Concrete Steps

Work from `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`.

Run targeted tests after implementation:

    ./gradlew :app:testDebugUnitTest :core-data:test :core-sources:test :core-stubs:test

Run the debug build after tests:

    ./gradlew :app:assembleDebug

## Validation and Acceptance

Manual acceptance requires opening Search, Creator Profile, and For You filter sheets and confirming the duration slider appears below the existing media/visibility controls. Narrowing the range must affect animated posts only; static posts remain visible unless `Animated only` is enabled. With the Settings switch off, unknown animated durations are hidden when the range is narrowed. With the switch on, unknown animated posts can resolve in the background and appear when their resolved duration matches the range.

## Idempotence and Recovery

The change is additive. Existing settings files that do not contain content-filter fields must load with `resolveUnknownAnimatedDurations = false`. Existing stored posts without `durationMs` must deserialize with `durationMs = null`. If source duration parsing fails, adapters should leave duration null and not fail the whole post.

## Artifacts and Notes

Unit test verification succeeded:

    ./gradlew :app:testDebugUnitTest :core-data:test :core-sources:test :core-stubs:test
    BUILD SUCCESSFUL in 3s

    ./gradlew :app:assembleDebug
    BUILD SUCCESSFUL in 1s

## Interfaces and Dependencies

The final code must expose:

- `Post.durationMs: Long?`
- `ContentFilterSettings(resolveUnknownAnimatedDurations: Boolean = false)`
- `SettingsRepository.setResolveUnknownAnimatedDurations(enabled: Boolean)`
- `AnimatedDurationRange(minBucket: Int = 0, maxBucket: Int = 25)`
- `filterSearchResults(..., unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy)`
