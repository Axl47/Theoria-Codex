# 0089. Hidden tags match within their own vocabulary and the picture tagger judges only sparsely tagged posts

Status: Accepted

## Context
Hidden tags must not hide posts from sources whose vocabulary differs, and the picture tagger is weaker evidence than a richly tagged provider's tags.

## Decision
- `hiddenBy` meets a post's tags with hidden tags written in its vocabulary: its source's and, for booru vocabulary, every booru source's, compared with `crossSourceTagKey`. A provider tag always counts, and a Hitomi post's own tags meet only Hitomi's hidden tags, so booru hidden tags leave them alone.
- A tag predicted with HIDE confidence is in Danbooru's vocabulary and counts only when `needsPictureCheck`, matched against booru vocabulary whatever the post's source, so a confident prediction on a sparsely tagged Hitomi post can match booru hidden tags: the provider gave fewer than `WELL_TAGGED` (10; booru posts carry about 25) tags in Danbooru's words. Tags outside the tagger's vocabulary do not count, and until the vocabulary is known every post counts as sparsely tagged, so nothing slips through while it loads.
- `hideCandidates` offers the tags the profile shows least interest in first, never universal tags (hiding one would hide nearly everything); among equals a content tag comes before artist, character, series, group, type, language, meta, rating or image-text tags.
- `swipeDirection` picks the axis by travel plus speed (`VELOCITY_REACH_SECONDS` per px/s), commits at a distance or fling, and a fling against the pull cancels.

## Consequences
New sources need a vocabulary decision before their hidden tags cross-match.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/triage/TriageRules.kt`: `hiddenBy`, `needsPictureCheck`, `WELL_TAGGED`, `hideCandidates`, `swipeDirection`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/triage/TriageRulesTest.kt`: `a provider tag meets the hidden tags written in its vocabulary (ADR 0089)`, `a predicted tag hides a sparsely tagged post when confident enough, through any booru source's hidden tags (ADR 0089)`, `a sparse Hitomi post's predicted tag meets the hidden tags of every booru source (ADR 0089)`, `a post its provider tagged well is left to its own tags, whatever its picture shows (ADR 0089)`, `hide candidates start with the tags the Likes carry least, in the post's order among equals (ADR 0089)`, `among equals a content tag beats artist, character, series, group, type, language, meta and rating tags (ADR 0089)`, `among equals text on the image comes after content, even where the source gives no tag types (ADR 0089)`, `a universal tag is never offered, however little it is liked (ADR 0089)`, `a drag commits in the direction of its axis once it passes the distance (ADR 0089)`, `a short slow drag springs back (ADR 0089)`, `a fling commits a short drag (ADR 0089)`, `a fling against the pull cancels it (ADR 0089)`, `a diagonal drag follows the axis it travelled further on (ADR 0089)`, `a fast flick picks its axis by speed as well as travel (ADR 0089)`
