# 0179. Swipes commit by velocity and distance with a catchable settle, and vertical galleries select only on reader scrolls

Status: Accepted

## Context
The Viewer drives its post and media pagers from one finger, over landscape and portrait pages, with a settle animation that a fast second flick can interrupt. A vertical still-image gallery is a long column whose pages resize as they load and which the Viewer itself scrolls (resume, page bar, strip), so scroll position cannot be trusted to mean the reader's choice.

## Decision
- `ViewerSwipeController` commits selection after the settle, or immediately when a new press catches the settle, so a fast flick continues from wherever the pages are instead of being dropped. Displacement is signed like the pager (negative moves towards Next) whichever way the layout runs.
- `viewerSwipeDecision`: a release at or above `VIEWER_FLING_VELOCITY` (400 dp/s) goes where it moves regardless of distance, and one moving back against this gesture's own `movement` cancels it; a slow release commits only past `commitFraction` of a page. `displacement` includes any offset left from a caught settle, so it says how far the pages are, not which way the finger went. Landscape pages (`viewport.width > height`) use `VIEWER_LANDSCAPE_COMMIT_FRACTION` (0.06) because they are about twice as wide.
- `dragViewerPage` scrolls with raw deltas so finger progress shows without committing the selected post before settling. `hasPendingGalleryMedia`: a sparse cover is not the gallery's last page even when its page count is unknown.
- Vertical gallery selection: the page under the selection line (a third down the screen) is selected, at either end the first or last page so short pages can be selected there (`focusedPage`). Selection follows only scrolls the reader makes (`VerticalGalleryScrollSelection`): pages also slide under the line when loading pages resize or the Viewer moves the list, and selecting those would answer the jump that moved them so the two trade pages back and forth. `scrollToSelection` therefore uses instant moves so composition never counts them as scrolls.
- The list position counts as the reader's choice only once it shows the selection (`aligned`); until then (resume resolving, restored page not yet scrolled to) publishing it would overwrite the saved page. The visible-media report is keyed by the post too so a chapter whose pages arrive while it shows is reported again with its pages.
- Before first layout `focusedPage` must not treat an empty list as scrolled to the end.
- Page shape: unpainted pages borrow the latest known shape, then the post's, and keep the one they first laid out with, so a neighbour painting does not shift pages on screen; a borrowed-shape page uses `fillWidth` so sizing the decode from it does not blur a tall strip. Painted/failed state is keyed by `media.ref` too because resolution can swap a cover for the full page under the same key and generation.
- The selected page reports readiness and failure to the Viewer as a horizontal page does (slideshow holds wait on it); the next-post card is padded clear of the dock.
- `verticalGalleryZoom` reads the Initial pass so the list never scrolls under a pinch; vertical drags stay with the list at finger speed. Zoom without a tap (TalkBack) anchors on the selected page (`zoomAnchor`). Taps toggle chrome except while TalkBack explores by touch, which keeps chrome up. The zoom action label reads `zoomed` through a derived state so it does not recompose on every pinch step.

## Consequences
A quick flick never gets lost mid-settle and slow drags feel the same on landscape and portrait. Programmatic scrolls and image resizes cannot change the reader's saved page. New list moves made by the Viewer must stay instant or be distinguished from reader scrolls.

## Applies to
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSwipeController.kt`: `ViewerSwipeController`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerSwipeNavigation.kt`: `viewerSwipeDecision`, `VIEWER_FLING_VELOCITY`, `VIEWER_LANDSCAPE_COMMIT_FRACTION`, `viewerCommitFraction`, `dragViewerPage`, `hasPendingGalleryMedia`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerVerticalGallery.kt`: `ViewerVerticalGallery`, `VerticalGalleryScrollSelection`, `VerticalGalleryEffects`, `focusedPage`, `scrollToSelection`, `verticalGalleryZoom`, `verticalGalleryTaps`, `ViewerVerticalGalleryState.zoomAnchor`
