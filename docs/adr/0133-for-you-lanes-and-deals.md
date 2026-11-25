# 0133. A Mix is a few lanes taken in turn across sources, topped up before they run dry and dealt in short, owed batches

Status: Accepted

## Context

A For You lane is one interest on one source (or one trending tag for a profile with no history) with its own paged feed. Sources answer at different speeds and some feeds answer with nothing the deck can show, so the Mix must give every source a turn without letting a slow source hold the first card back or a barren feed be paged forever.

## Decision

- `ForYouLanesLoader` takes each source's seeds in turn (every source's first seed, then every source's second, and so on) so every source gets a lane, up to `LANE_LIMIT` (6), because each lane opens with its own request and has an entry in Filters' Mix list. A profile with no history gets up to `TRENDING_LANES_PER_SOURCE` (3) trending tags per source, without file tags: what trends on a source is often its medium (animated, 3d), which says nothing about an interest. Seeds that a hidden tag hides are left out (`hidesSeed`).
- `hidesSeed` is the one rule for what hidden tags hide: an entry hides every tag set on its source that contains all of its tags, so hiding "boats" hides each lane that includes boats. Tags are spelled as the cards' hidden tags are (`normalizeMatchToken`), so a lane and its posts are hidden by the same entry. The per-post filter is narrower: `ForYouDeckViewModel.synchronize` passes only single-tag entries to it (`hiddenTokens`), so a multi-tag entry hides lanes through `hidesSeed` but not individual posts.
- `availableInterests` picks again without any tag the source reports no posts for, because its lane would stay empty: counts are asked only for the picked tags, up to `AVAILABILITY_ROUNDS` (3) picks, after which interests with such tags drop. Candidates are weighed once, before the rounds. Predicted picture tags are Danbooru's words, spelled for the source when it shares that vocabulary.
- Each lane's query asks the source for animated media where it can; the deck's owner filters again for sources that cannot.
- Every lane is asked before the first deal, so lanes that answer without waiting are dealt in turn; after that the first lane to answer deals, and a slow source does not hold the first card back. A lane's request starts in place, so one that answers without waiting has answered by the time `load` returns.
- A lane asks for its next page once it holds fewer than `LOW_WATER` (6) posts, vetted or not, and pages are asked for one lane at a time since a lane that answers at once refills too. A lane's cursor ends when its feed ends or after `EMPTY_PAGE_LIMIT` (3) pages in a row gave the deck nothing, so a feed that keeps answering with nothing the deck can show is not paged forever; a page whose posts all wait for vetting counts as empty until one passes. A failed lane keeps its page for retry and shows in the status row, one failure per source.
- A deal that came up short is owed: the next cards to arrive are dealt at once (`wanted`, `owed`), and before any lane has answered nothing is owed but the whole first deal. `snapshot` reports loading before the first answer and a page failure only when nothing is buffered, vetting or loading and a running lane failed, so lanes that still give cards keep the deck going.
- Lanes with the same label gain their source's name ("fog · Pixiv"). `hideLanes` leaves out the plan's lanes a hidden tag hides, with their steering, and keeps what they had not dealt; `addLanes` appends planned lanes the Mix does not have (such as lanes a hide kept out when it was planned), skipping any lane whose id the Mix already has. Lanes are not merged by label: a new lane whose label another lane in the combined set shares gets its source's name appended, and lanes already in the Mix keep the label they have.

## Consequences

Every source is visible in the first Mix at the cost of at most six requests opening together, and a feed with nothing to show stops costing requests. Changing `LANE_LIMIT` or `LOW_WATER` changes both request volume and how soon the first card appears.

## Applies to

- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouLanes.kt`: `ForYouLanesLoader`, `availableInterests`, `trendingSeeds`, `inTurnBySource`, `LANE_LIMIT`, `AVAILABILITY_ROUNDS`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouBlacklistMatch.kt`: `hidesSeed`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouMix.kt`: `MixLane`, `ForYouMix`, `LOW_WATER`, `EMPTY_PAGE_LIMIT`, `snapshot`, `labelAmong`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckViewModel.kt`: `rebuild`, `load`, `addLanes`, `hideLanes`, `synchronize`, `refill`, `deal`, `makeUp`
