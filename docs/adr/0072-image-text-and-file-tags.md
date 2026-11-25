# 0072. Tags about text on the image or about the file are never counted as interests

Status: Accepted

## Context
Sources file watermarks, handles, mediums, lengths and resolutions as ordinary tags. On what a profile keeps they can be as much more common as a followed series, so no count separates them from real interests.

## Decision
- `isImageTextTag` matches a fixed list plus handle and logo endings, so a new platform needs no change; `isFileTag` matches medium, motion, sound, length, resolution and filing tags by list, prefix and ending, so new families need no change.
- Neither kind is offered as an interest unless the profile chose it.
- Picture evidence uses only predictions with evidence confidence, never image-text tags, and not tags on half of Danbooru's posts (`PictureEvidence`, `evidence`). `keptByTag` sums scores, `postsByTag` counts posts, `baseRates` holds Danbooru shares, all keyed by `normalizeMatchToken` spelling.

## Consequences
New tag families are caught by wording; truly new vocabulary needs a list entry.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `isImageTextTag`, `isFileTag`, `PictureEvidence`, `evidence`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/TagAffinityTest.kt`: `text drawn on the image is never offered unless chosen, however specific (ADR 0072)`, `a tag about the file is never offered unless chosen, however much more often it is kept (ADR 0072)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/ForYouInterestsTest.kt`: `before any history, text on the image and tags about the file never lead (ADR 0071, 0072)`, `a tag about the file proves nothing by its picture, though a profile that chose it still gets it (ADR 0072, 0087)`, `text on the image and tags on half of Danbooru are never evidence (ADR 0072)`, `picture evidence counts kept posts' confident predictions, weighted by score (ADR 0072, 0085)`
- Left to prose: membership of every single list entry, ending and prefix is unguarded; the tests check each kind of match with one member.
