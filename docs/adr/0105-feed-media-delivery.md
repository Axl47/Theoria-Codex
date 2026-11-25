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

## Guarded by

- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostMediaTest.kt`: `gelbooru preview upgrades to its sample but leaves the original to the Viewer (ADR 0105)`, `pixiv preview uses saved aspect preserving progressive image (ADR 0105)`, `every source treats a first page's original as a card fallback and steps a small original up (ADR 0105)`, `rule34xxx keeps cards on the preview and shows the sample first in the Viewer (ADR 0105)`, `remote gif paints its poster first and never upgrades to a static sample (ADR 0105)`, `animated mirrors are alternatives for a single upgrade (ADR 0105)`, `media requests carry the source's own agent (ADR 0105)`, `motion reads the lightest video rendition with the source's headers, or the local copy (ADR 0105)`, `a Pixiv animation plays from its archive, while stills and animated images keep their still (ADR 0105)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/DeliveryPlanSourceParityTest.kt`: `the Viewer plan for the same media is the same for every source (ADR 0074, 0105)`, `the card plan for the same media is the same for every source but Pixiv and rule34xxx (ADR 0074, 0105)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/viewer/LazyMediaResolutionTest.kt`: `each source decides whether its remote video refreshes its link, and a saved copy never does (ADR 0105)`, `each source decides whether a post whose listing named no media address is resolved when opened (ADR 0105)`, `an episode is not resolved lazily (ADR 0105)`, `a gallery whose listing named no pages resolves whatever its source (ADR 0105)`
- `scripts/tests/test_check_architecture.py`: `test_reports_a_delivery_plan_that_decides_from_a_source_adr_0074_adr_0105`
- Left to prose: `requiresLazyMediaResolution` decides from lists of sources rather than from what a post's media offers; its test makes every built-in source and a custom site take a decision, so a new source fails it until one is made. That an episode's streams are fetched as it opens and its expiring links come back through the Viewer's media recovery is not yet guarded, testable in feature-viewer; whether the poster-then-animation swap looks right is judged on a device.
