# 0074. Media policy decides from what the media offers, not from source names

Status: Accepted

## Context
Feed cards are small and often on metered networks, while series episodes expose HLS or DASH addresses that name no media type. Quality and kind decisions must apply to every source.

## Decision
- `mediaKind` treats any ref with streams as video, since stream addresses often name no type in their location.
- `withVideoQuality` selects a rendition without changing gallery identity or canonical download metadata, and does nothing for local files or refs without variants.
- `feedPreviewLocation` always previews the Data saver rendition on a metered network regardless of Viewer quality; unmetered feeds keep the default stream. Cards still autoplay together.
- `replacingPostSnapshot` updates one card without admitting posts or changing order and keeps the listing's comment hint, which detail responses seldom repeat.
- `ugoiraFrameSampleSize` trades preview resolution for bounded complete playback of long animations and keeps export quality.
- `MediaStorageBreakdown` categories are disjoint stores (downloads live in the public Downloads folder); percentages share the total of all managed media.
- Delivery plans upgrade quality from the media's renditions and size, never from lists of sources; `PostMediaTest` pins this for the preview plan.

## Consequences
New sources get delivery optimizations automatically if their media offers variants or streams.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/PostMediaPolicy.kt`: `mediaKind`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/VideoQualityPolicy.kt`: `withVideoQuality`, `feedPreviewLocation`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/PostSnapshotUpdates.kt`: `replacingPostSnapshot`
- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/UgoiraDecodePolicy.kt`: `ugoiraFrameSampleSize`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/MediaStorageBreakdown.kt`: `MediaStorageBreakdown`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostMediaTest.kt`: `PostMediaTest`
