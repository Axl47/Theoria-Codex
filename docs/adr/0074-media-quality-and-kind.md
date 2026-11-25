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
- Delivery plans upgrade quality from the media's renditions and size, never from lists of sources. `DeliveryPlanSourceParityTest` pins this for card and Viewer plans, with and without lighter renditions. Two source-based exceptions exist, each guarded against its own expected plan: `postPreviewImageCandidate` starts a Pixiv card from the medium rendition, because Pixiv's square preview crops the artwork and the medium image keeps its framing; and a source whose presentation sets `cardsStayOnPreview` (rule34.xxx) never steps a card up, as [0105](0105-feed-media-delivery.md) records. `delivery_plans_ignore_source_names` allows only the Pixiv branch by its exact text and rejects any other source or `isCustom` test in either plan.
- `isAnimatedPost` has one exception, decided from the source: Iwara, Rule34Video and Rule34Gen list only videos, and their listings carry a JPEG thumbnail and no media until the post is opened, so the media cannot tell yet. Posts from these three sources count as animated. Its Pixiv ugoira check names Pixiv but agrees with the media kind, since the ugoira mime type already makes `mediaKind` return ugoira from any source; no other source key changes the answer.

## Consequences
New sources get delivery optimizations automatically if their media offers variants or streams.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/PostMediaPolicy.kt`: `mediaKind`, `isAnimatedPost`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/VideoQualityPolicy.kt`: `withVideoQuality`, `feedPreviewLocation`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/PostSnapshotUpdates.kt`: `replacingPostSnapshot`
- `app-logic/src/main/kotlin/com/theoriacodex/app/viewer/UgoiraDecodePolicy.kt`: `ugoiraFrameSampleSize`
- `app-logic/src/main/kotlin/com/theoriacodex/app/media/MediaStorageBreakdown.kt`: `MediaStorageBreakdown`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/PostMediaTest.kt`: `PostMediaTest`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt`: `previewMediaDeliveryPlan`, `mediaDeliveryPlan`, `hasLighterRenditions`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/media/PostMediaPolicyTest.kt`: `a ref with streams is video even when its address and mime name no type (ADR 0074)`, `an Iwara, Rule34Video or Rule34Gen listing post is animated before its media is known (ADR 0074)`, `no other source key decides whether a post is animated (ADR 0074)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/VideoQualityPolicyTest.kt`: `network defaults and explicit choices select renditions of one item (ADR 0074)`, `offline media and unavailable resolution retain usable media (ADR 0074)`, `feed cards use the smallest sensible stream only on metered networks (ADR 0074)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/PostSnapshotUpdatesTest.kt`: `a snapshot replaces its own card in place, admits nothing and keeps the listing's comment hint (ADR 0074)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/viewer/UgoiraDecodePolicyTest.kt`: `a long animation decodes smaller to fit its playback budget while short ones and exports keep resolution (ADR 0074)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/media/MediaStorageBreakdownTest.kt`: `every category, downloads included, shares one byte accurate total (ADR 0074)`
- `media-kit/src/test/kotlin/com/theoriacodex/app/media/DeliveryPlanSourceParityTest.kt`: `the Viewer plan for the same media is the same for every source (ADR 0074)`, `the card plan for the same media is the same for every source but Pixiv and rule34xxx (ADR 0074)`, `a card without lighter renditions keeps its preview and falls back to the original for every source (ADR 0074)`, `Pixiv cards start from the medium rendition, which keeps the artwork's framing (ADR 0074)`, `a source whose cards stay on the preview never steps up, though its Viewer does (ADR 0074)`
- `scripts/tests/test_check_architecture.py`: `test_reports_a_delivery_plan_that_decides_from_a_source_adr_0074`, `test_reports_a_preview_candidate_that_decides_from_a_source_id_adr_0074`, `test_allows_only_the_named_pixiv_medium_first_exception_adr_0074`
- Left to prose: that the storage categories are disjoint stores, with downloads in the public Downloads folder, is unguarded because it is file layout decided where each store writes; that cards still autoplay together under the feed previews is unguarded because it is playback on a device.
