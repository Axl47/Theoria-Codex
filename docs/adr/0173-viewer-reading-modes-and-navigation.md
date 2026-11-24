# 0173. Reading mode, entry page and recommendation branches follow fixed rules, and the info sheet opens where it was asked to

Status: Accepted

## Context

A post can be a single image, a gallery or a series chapter, read horizontally or as a vertical column. Recommendation branches add posts that are not part of the original feed, and the info sheet can be opened from several places.

## Decision

- `readingModeOf`: the mode chosen for the gallery, else the global direction. A single page always runs left to right, except a series' one-page chapter, which keeps the series' mode so the gesture to the next chapter stays the one the reader has been making. A saved Vertical gives way while the gallery holds pages that are not still images (`verticalReadingAvailable`), because video and animation need the pager's one active page to play.
- `entryMediaIndex`: a post opens where it was left, except a series chapter left on its last page, which is finished and starts over, unless the reader is going back to it from the next chapter, in which case paging back continues from the end.
- Only the shown layout's zoom holds the gestures: a horizontal zoom left over from before a switch to Vertical must not. Pinches write the transform every pointer event, so the pager reads only the zoomed boundary. Each vertical gallery's scroll and zoom is kept while its column leaves composition (the page overview replaces it).
- `ViewerPostPageEffects` keeps a post's media pager and the selection in step, and their order and keys are load-bearing; `mediaCount` is a key so a post whose pages arrive while it shows is reported again with its pages. Any touch on the media stops a running slideshow.
- Recommendation branches are transient: cold recovery resumes the original feed at its liked anchor (`forOriginalNavigation`); display order can visit a branch while backing indices and the original feed stay stable. `ViewerRelatedStrip` collapse is local and survives new recommendations; only an explicit Dismiss clears the source shelf and its retained liked seeds. The drawer may hold posts from earlier recommendations, so opening one of those keeps it in scope.
- Info sheet: it opens at the top unless asked for comments. The target is read as the sheet opens and the sheet resets it on close, so any other way of opening starts at the top. A comment a link led to is taken from the post's comments state, which clears it so it acts once, and is tinted for `COMMENT_HIGHLIGHT_MS`. Whether it opened at comments and whether tags start collapsed (the post has comments) settle as the sheet opens and do not move later. Opening an author's creator page pauses playback and closes the sheet, as for the post's own creator.
- A series entry's sheet holds only its title and Start over: a chapter or episode is not a post to collect, share or search from (no link, tags or creator, media loads only through its extension, Search cannot reach its source). For an episode, starting over seeks to its start and closes any open panel, since the sheet would hide it.

## Consequences

Adding a media kind that cannot render in a column must keep `verticalReadingAvailable` honest.

## Applies to

- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerReadingMode.kt`: `readingModeOf`, `entryMediaIndex`, `verticalReadingAvailable`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostPager.kt`: `ViewerPostPagerSurface`, `ViewerPostPageEffects`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerPostNavigation.kt`: `ViewerPostNavigation`, `forOriginalNavigation`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerRelatedStrip.kt`: `ViewerRelatedStrip`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerScreenState.kt`: `ViewerScreenState.verticalGalleryPositions`, `infoSheetTarget`, `restartViewerEpisode`
- `feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/ViewerInfoSheet.kt`: `rememberHighlightedComment`, `PostInfoSheet`, `ViewerChapterActions`, `commentsView`
