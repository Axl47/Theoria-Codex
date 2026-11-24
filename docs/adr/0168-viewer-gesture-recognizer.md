# 0168. One Viewer recognizer owns every one-finger gesture, with a fixed order of direction-lock rules

Status: Accepted

## Context

Levels, navigation, taps, pinches, dismissal and info all start from a single finger on the media. A finger must keep its gesture even when the page it landed on slides away, and system Back, long presses and vertical galleries each claim touches at different moments.

## Decision

- `viewerGestures` sits above the pagers and reads `ViewerGestureConfig` per gesture, so selection changes cannot restart it. One direction lock separates level adjustment, navigation, taps and pinches.
- `lockedMode` decides in this order: a hold-to-speed-up already running makes a horizontal drag a scrub and a vertical one Held; a finger that locks only after the long-press timeout is Held (the press already sped playback up or opened info, because an unlocked drag stays unconsumed); a horizontal drag from a system gesture edge is Held (Back takes the touch after it starts); a vertical gallery decides by its ends (pull down at the top dismisses, push up at the end opens info, otherwise the list scrolls and the recognizer leaves events unconsumed); then an outer column adjusts its level; otherwise a pull down dismisses and a push up opens info. Level adjustments are off in a vertical gallery.
- Columns: a wide area keeps its outer columns to a thumb's reach (`viewerGestureColumn`). Only seekable media reserves the outer columns for double-tap seeking; zoomed media always double-taps back to fit. Brightness is on the leading column for every media; the trailing column adjusts volume only for media with sound, otherwise it stays for closing and info.
- Release velocity uses historical touch samples, because frames batch samples and a flick during a busy frame would read as slow. Within `VIEWER_FLICK_WINDOW_MS` (150 ms) the gesture's average speed is a floor (`viewerReleaseVelocity`), since a busy main thread can deliver a flick as little more than its down and up.
- A lift that arrives already consumed means the system took the touch (the Back gesture does, mid-swipe) and must not read as a flick to another post.
- An upward gesture that would open info opens the next chapter instead when the series is being read in a vertical gallery and a next chapter exists (`openNextChapter` in `viewerGestureHandler`, from `chapterPushUp`). While the chapter on screen still waits for its pages (`awaitsChapterPages`) the push up does nothing: its only page is the cover, which always fits the screen, so every upward swipe would otherwise land at the end and leave the chapter. A spinner (`ChapterPagesLoading`) shows over a chapter until its pages arrive or fail. A gesture reports to the handler it was pressed with (`viewerGestures`), so a push up begun on a loading chapter stays inert even if the pages arrive before the finger lifts.
- `swipeTargetFrom`: a vertical gallery scrolls its own pages, so a swipe there always changes post.

## Consequences

New gestures must slot into the lock order. Changing the long-press or back-edge rules changes how Back, speed-up and info interact.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerMediaGestures.kt`: `viewerGestures`, `ViewerGestureTracker`, `ViewerGestureConfig`, `ViewerVerticalScroll`, `viewerGestureColumn`, `doubleTap`, `viewerReleaseVelocity`, `VIEWER_FLICK_WINDOW_MS`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerFrameEffects.kt`: `swipeTargetFrom`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostPager.kt`: `viewerGestureHandler`, `nextPostOpener`, `chapterPushUp`, `ChapterPagesLoading`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReadingMode.kt`: `awaitsChapterPages`

## Guarded by

- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `a sideways swipe in a vertical gallery moves to the next post (ADR 0168)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `pulling down at the top of a vertical gallery closes the Viewer (ADR 0168)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `a push up on a chapter still loading its pages stays on the chapter and shows it loading (ADR 0168)`
- `feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGalleryTest.kt`: `a push up that began on a loading chapter stays on it when its pages arrive mid-swipe (ADR 0168)`
