# 0172. The post strip and page scrubber animate as one eased motion, hold the touched page, and load previews only once the finger rests

Status: Accepted

## Context

The strip below the media unfolds a multi-page post in place, and the page scrubber seeks whole pages. Both are dragged, so layout must not jump as posts fold and unfold, a rolling fingertip must not flip pages, and a fast drag must not hammer providers with downloads.

## Decision

- `ViewerStripMotion` makes every change of post one eased motion: each slot's width and the centering read the same progress, so the row never jumps as one post folds and another unfolds, and a new tap carries on from wherever the last one had reached. `CenterSelectedPost` works per frame so widths and scroll land in the same layout; a finger on the strip wins.
- Unfolded pages are always laid out at full width and revealed by a clip, so opening never disturbs their own scroller. `rememberPagesWhileOpen` keeps a post's last pages while it is still folding shut. An unfolded post's pages scroll inside all but the outermost slots so neighbouring posts stay in reach at both ends. Slot width is read at layout, so folding does not recompose.
- Emphasis on a thumbnail is drawn, not laid out, so slots and centering stay put as selection moves; resting thumbnails are dimmed rather than made transparent so the cards stacked behind never show through. The page stack rises rather than spreads because gaps between slots are too narrow. `SELECTED_THUMBNAIL_GROWTH` is small enough that a grown thumbnail never overlaps its neighbours, in the strip or the related shelf. Series chapters are named by their titles because their ids are addresses.
- `viewerPageAt`: each page owns an equal share of the track, and a held page keeps the touch until it is `holdPx` (at most half a share) past its share, so a finger resting on a boundary or rolling as it lifts does not flip between pages. Long posts give each page a sliver, so this matters most there. A drag floats the target page and its neighbours and turns the page once, on release; the scrubber is keyed by post so a drag held across a post change is cancelled instead of landing on the new post.
- `ViewerPagePreview`: the readout follows the finger, but images wait for it to rest (`SCRUB_FRAME_SETTLE_MS`), sparing providers a fast drag; the original waits longer (`VIEWER_SHARP_SETTLE_MS`) and is compared with the settled page rather than reset so the sharp page never flashes back to the thumbnail. Frames are keyed by page, not position, so a frame sliding to the centre keeps its load; the centre keeps the last loaded thumbnail as a placeholder so a jump that replaces all three frames leaves nothing empty; the placeholder is taken once when the frame appears, since rebuilding the request restarts its load; the original goes over the thumbnail, which stays until it has loaded.
- `PageTopCrop` crops a page to its thumbnail's shape from the top, past the blank margin pages usually open with (`pageTopStart`): the margin colour is the one most of the top row shares, a row is content once at least two samples (one in `CONTENT_SHARE`) differ beyond `CHANNEL_TOLERANCE` (JPEG noise on a flat margin), and a sixteenth of the width is kept above the content. A page with no content keeps its top; content lower than the last full window keeps that window.

## Consequences

Strip changes must keep widths and centering reading one progress value. Tuning settle delays trades provider load against responsiveness.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostStrip.kt`: `ViewerStripMotion`, `CenterSelectedPost`, `rememberPagesWhileOpen`, `stripSlot`, `thumbnailSurface`, `drawPageStack`, `ViewerUnfoldedPages`, `SELECTED_THUMBNAIL_GROWTH`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPageScrubber.kt`: `ViewerPageScrubber`, `viewerPageAt`, `ViewerPagePreview`, `ViewerPagePreviewFrame`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPageTop.kt`: `PageTopCrop`, `pageTopStart`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenChrome.kt`: `ViewerBottomStack`
