# 0087. Tag affinity compares kept posts with watched posts and discounts universal tags

Status: Accepted

## Context
Recommendations and Trails need to rank a post's tags by how characteristic they are of the profile. Counting Likes alone favours tags present on everything, such as "image" on nearly every post.

## Decision
- `TagAffinityInputs` ranks tags from Likes, kept posts (Liked or in a Codex, each once), explicit choices and watched statistics. An explicit choice counts as `EXPLICIT_WEIGHT` Likes and adds `EXPLICIT_BONUS` to score, about a tag on ten kept posts at four times its watched rate.
- `lift` compares the share on kept posts with the share on watched posts of that source, and is null until `hasHistory` (enough watched and kept posts). `historyScore` is ln(lift) times ln(1 + kept), so a tag kept once is still a guess. `LIFT_PSEUDO_COUNT` keeps lifts finite.
- A tag is universal when it is on most watched posts (`UNIVERSAL_WATCHED_SHARE`) even if chosen; before enough watched posts, a tag on nearly every Like stands in unless chosen; and a tag on `UNIVERSAL_FEED_SHARE` of the current feed is universal there (`feedTagShare`, ignored below `MIN_FEED_POSTS`). Universal tags are never offered.
- A tag absent from a source's capped watched table (see [0030](0030-statistics-recording.md)) may have been watched up to that source's floor (`watchedTagFloors`). Each judgement errs toward not claiming: `lift` reads it at the floor (`watchedShareAtMost`), so absence never makes a tag proven, while universality and the too-rare-to-judge check read the count the table guarantees, its estimate minus the source's floor (`watchedShare`, `watchedAtLeast`), so neither absence nor an inherited count makes a tag universal or judged, and a tag kept once can still be the guess lane. Absent tags all count as unwatched there, so guesses among them are not ordered by rarity. A niche tag kept on a few posts and absent from the table is therefore not proven.
- A tag too rare in what was watched to judge is offered after proven tags, rarest first. `UNKNOWN_TAG_INTEREST` lets rarity order tags of an unfamiliar post.
- Picture-predicted tags (`pictureAffinity`) compare the kept share with the watched share, never below the Danbooru base rate; with no watched history the share must beat the base rate `COLD_PICTURE_LIFT` times because the profile's sources are not Danbooru. They are proven like provider tags and are never universal, text on the image, or about the file unless chosen through `affinity`.

## Consequences
Thresholds interact; change the share constants together.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `TagAffinityInputs`, `lift`, `watchedShare`, `watchedShareAtMost`, `watchedAtMost`, `watchedAtLeast`, `hasHistory`, `TagAffinity`, `affinity`, `pictureAffinity`, `historyScore`, `feedTagShare`, `EXPLICIT_WEIGHT`, `EXPLICIT_BONUS`, `COLD_PICTURE_LIFT`, `LIFT_PSEUDO_COUNT`, `UNKNOWN_TAG_INTEREST`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/TagAffinityTest.kt`: `saved tags, saved searches and repeated searches outrank incidental Likes (ADR 0087)`, `Codex posts count as interest like Likes do (ADR 0087)`, `a post both Liked and in a Codex is one point of interest (ADR 0085, 0087)`, `with enough history a tag must be clearly more common in what was kept than in what was watched (ADR 0087)`, `lift compares each source with itself, and a chosen tag on most watched posts is still universal (ADR 0087)`, `a tag on most watched posts is universal, so specific liked tags lead (ADR 0087)`, `before enough watched posts, Likes alone mark a tag universal unless it was chosen explicitly (ADR 0087)`, `a tag on nearly every post of the feed at hand is universal there (ADR 0087)`, `a tag dropped from a full source reads at most the source's floor, so its lift on kept posts cannot spike (ADR 0087)`, `a count inherited from the floor never makes a tag universal or a picture tag proven (ADR 0087)`, `a dropped tag kept once stays too rare to judge, so it can still be guessed (ADR 0087)`, `a tag too rare in what was watched to judge is still offered, after the proven ones (ADR 0087)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/ForYouInterestsTest.kt`: `a tag predicted on many kept pictures leads a lane, compared with what the profile watches (ADR 0071, 0087)`, `before the source has watched enough, a predicted tag must beat its Danbooru share four times (ADR 0087)`, `a predicted tag on nearly every kept post is universal, however rare on Danbooru (ADR 0087)`, `a tag about the file proves nothing by its picture, though a profile that chose it still gets it (ADR 0072, 0087)`
- Left to prose: the share thresholds are tuned together, and the score bonus a chosen tag gets once the source has history is unguarded, since a chosen tag is proven anyway and the bonus only moves it among proven tags.
