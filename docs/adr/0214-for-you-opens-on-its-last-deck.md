# 0214. For You opens on the cards it left undecided, and they lead the fresh Mix

Status: Accepted

## Context

At launch, For You showed nothing until its lanes were planned, the recent skips were read and a lane's first page came back from the network. Search already paints its last first page from a disposable cache file and replaces it while the grid is unscrolled (ADRs 0021, 0080 and 0082). A deck is different: its first page is swiped through within seconds, so a saved first page would be cards the profile already skipped, and replacing the card on top would move it while the user is looking at it. The other Home tabs draw from local data, except Library's live Saved shelves, which are shelves inside a section rather than the tab's feed.

## Decision

- The deck keeps up to `KEPT_DECK_SIZE` (12) Mix cards not yet decided in this session, cards dealt in this session first and cards it opened on after them (`freshFirst`), filtered like a deal before the cap, and recomputed on every Mix publish, every decision and every change to liked or saved posts, in a single-slot cache file (`FirstPageStore`, `for_you_deck.json`) keyed by profile, picked source, sort and animated-only. A chip's narrowed Mix is not kept. Writes go through one conflated channel drained in the activity's write scope, so the newest deck lands after the tab is gone, and an unchanged deck is not written again.
- Only a ViewModel's first build that has sources reads the deck, alongside lane planning; that build consumes the chance whatever happens to it. Cards are shown only once this profile's recent skips (ADR 0209) are in memory when both the file read and the bounded skip wait have finished. A read that fails, or has not landed by then, shows no kept cards. A shown card passes the same exclusions as a fresh deal: this session's decisions, the 30-day skips and liked, saved or pending-like posts (by id and known copies), hidden tags, the animated-only filter, sources that are still enabled, and the picked source. A card still waiting for a picture check is left out rather than vetted.
- Kept cards are never replaced. The Mix is built with them as its first released cards and deals fresh cards behind them, so a deck that only grows never moves what is shown. Building the Mix waits for this step, which is local and bounded by the skip read. Because the next launch leads with the cards dealt behind them, an undecided card leads at most one launch while fresh cards arrive, so neither the deck nor the grid's first screen stays pinned.
- Every later build (Refresh, Start over, Retry, a chip, a changed profile or filter) deals a fresh Mix without kept cards, even while the first build is still planning. A chip's rebuilt Mix drops undecided kept cards, since they belong to no lane. When lane building fails or finds no lanes, the cards already shown stay, with the failure or end state after them; the grid shows its Retry tile above them. A reconnect does not rebuild over shown cards; Retry does.
- Liking or saving a kept card asks for similar or creator posts like any card; one decided before the lanes are planned asks once the Mix exists.
- Kept cards are first content for `reportFullyDrawn` (ADR 0213), as Search's cached page already is.
- Deleting any profile clears the file, and the store refuses writes for a retired profile under the same lock as the clear, so a write in flight cannot bring a deleted profile's deck back. Settings' cache clearing deletes it with Search's, and clearing app data removes the cache directory.
- Search's cached page stays keyed by its search alone: Search keeps one applied search for all profiles (`LAST_ACTIVE_QUERY_KEY`), so its page belongs to no profile.

## Consequences

A relaunch opens on the cards that were next, from the disk image and video caches, while the network catches up. Kept cards have no lane origin, so they show no "from" line and do not steer the lanes; the fresh cards behind them do. The file holds one profile's deck: switching profiles and relaunching shows nothing until the new profile has kept a deck. In the grid, cards scrolled past without a decision stay undecided, so they come back after the newer cards on a later launch. Kept cards carry no age, so after weeks unopened they can show media the disk cache no longer holds. A card whose listing has no playable stream resolves its media when shown, as a fresh card does, so it needs the network before it plays. Library's live shelves still load from the network at launch; caching them would be a separate change to `LiveCodexFeedController`.

## Applies to

- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckViewModel.kt`: `rebuild`, `paintLastDeck`, `keepUpcoming`, `holdAsk`, `settle`, `KEPT_DECK_SIZE`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouMix.kt`: `ForYouMix` (`leading`), `freshFirst`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouCoordinator.kt`: `lastDeck`, `rememberDeck`, `deckWritable`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouExecution.kt`: `deckKey`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouVerdictLog.kt`: `hasSkipsFor`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/FirstPageStore.kt`: `FirstPageStore`, `save` (`admit`), `clear`, `FOR_YOU_FILE_NAME`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ProfileDeletionService.kt`: `removeProfileCaches`
- `media-kit/src/main/kotlin/com/theoriacodex/app/media/MediaCacheMaintenance.kt`: `clear`
