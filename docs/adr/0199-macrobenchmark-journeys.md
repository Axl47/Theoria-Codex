# 0199. Benchmark journeys use the real UI and judge playback from sampled pixels, skipping autoplay checks on emulator decoders

Status: Accepted

## Context

The performance journeys exist to prove that every visibly presented animated card plays at once. Player state flags and synthetic navigation can report success while nothing moves on screen, and the emulator's video decoders cannot play the fixture's twelve videos together.

## Decision

- `completeFiveFeedJourney` uses the real bottom navigation, collection card and creator action; there is no synthetic feed switching. After the journey sends the app Home, it resumes by reordering the existing fixture activity to the front (`FLAG_ACTIVITY_REORDER_TO_FRONT`); there is no `CLEAR_TASK` and no production launcher activity, and the journey checks the session identity is unchanged so route owners were retained.
- `requireChangingMedia` samples the actual image surface of every visible preview (`screenshotSamples`), so paused frames, spinner-only previews and `isPlaying`-only reports cannot satisfy the test; all visible identities must advance, siblings included. The For You deck presents one card, which must play like any visible preview; Feed previews (`requireMovingFeedPreviews`) require at least two visible. The Viewer snapshot comes from the benchmark-only receiver in the app and gives the canonical identity and fitted image bounds, so moving controls cannot satisfy pixel sampling. `requirePreviewLeaseCount` asks that same receiver, which answers from the application-owned player pool.
- `visiblePreviews` retries the whole snapshot when a node goes stale, since dropping one stale node could omit a visibly playing sibling.
- `assumeSearchAutoplayDecodersAvailable` skips the Search autoplay scenarios on emulators (`Build.HARDWARE` of `goldfish` or `ranchu`): the goldfish H.264 decoder has four host slots, Media3 falls back to the software decoder for the rest, and from about six software decoders on at least one card stops playing. Phones decode them all in hardware, so the simultaneous-autoplay assertion runs there unchanged.
- The baseline-profile generator drives the paths users repeat most through the offline fixture app, so no provider is contacted.

## Consequences

Emulators cannot establish simultaneous autoplay; only a phone can. A new journey step should assert on sampled pixels or the lease receiver, not on player state.

## Applies to

- `macrobenchmark/src/main/java/com/theoriacodex/macrobenchmark/MixedMediaFeedJourney.kt`: `completeFiveFeedJourney`, `requireChangingMedia`, `requireMovingFeedPreviews`, `viewerPreviewSnapshot`, `visiblePreviews`, `requirePreviewLeaseCount`
- `macrobenchmark/src/main/java/com/theoriacodex/macrobenchmark/TheoriaMacrobenchmark.kt`: `assumeSearchAutoplayDecodersAvailable`
- `baseline-profile/src/main/java/com/theoriacodex/baselineprofile/BaselineProfileGenerator.kt`: `feedViewerAndSearchEditor`
