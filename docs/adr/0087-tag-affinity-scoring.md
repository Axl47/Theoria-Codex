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
