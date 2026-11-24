# 0105. Feed cards load the preview first, step up only to a downscaled rendition, and lazy media links are refreshed for booru families

Status: Accepted

## Context

Feed cards render at most half the screen wide, many cards autoplay at once, and remote animated images must download completely before their first frame. Downloading originals just to downsample wastes data and bandwidth.

## Decision

- `previewMediaDeliveryPlan` paints the poster first and swaps to the animation once it is ready. Quality upgrades stop at the largest downscaled rendition (Gelbooru sample, Pixiv large); originals stay failure fallbacks left to the Viewer: a card tries one only when every earlier candidate failed. An original with no lighter rendition is the step up only when the post says it is small (`FEED_SMALL_ORIGINAL_MAX_EDGE_PX`, 1280), which is when boorus skip generating a sample. `feedOriginalLocations` lists originals that have a smaller rendition so they never count as quality upgrades.
- `animatedImageLocations` offers only the animated URL and its animated mirrors: a static sample must never replace a playing animation.
- Sources can opt out of the step-up with `cardsStayOnPreview` (a sample on every card would download far more than the previews the feed was tuned around). The Viewer still steps up. Media requests use the source's own User-Agent (Danbooru's CDN challenges generic browser agents).
- `postMotion` offers the picture tagger a post's video at the lightest listed rendition (the model reads 448 px squares, as metered feeds pick), or its Pixiv animation. Animated GIF, WebP, APNG and AVIF images give null and keep their still: they are short loops whose first frame the preview shows, and the platform cannot step through WebP, APNG or AVIF frames.
- `requiresLazyMediaResolution` refreshes media links for built-in boorus and for custom sources. An episode's streams are fetched as it opens; their expiring links come back through the Viewer's media recovery. A gallery whose listing named no pages gets them from its lookup, whatever its source.

## Consequences

Cards stay light and deterministic across sources; changing a source's rendition list changes what its cards load without touching the plan.

## Applies to

- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt`: `previewMediaDeliveryPlan`, `FEED_SMALL_ORIGINAL_MAX_EDGE_PX`, `feedOriginalLocations`, `animatedImageLocations`, `postMotion`
- `media-kit/src/main/kotlin/com/theoriacodex/app/source/SourceMetadata.kt`: `SourcePresentation.cardsStayOnPreview`, `SourcePresentation.userAgent`
- `media-kit/src/main/kotlin/com/theoriacodex/app/viewer/LazyMediaResolution.kt`: `requiresLazyMediaResolution`, `hasRefreshableRemoteMedia`
