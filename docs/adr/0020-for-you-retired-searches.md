# 0020. Saved For You searches are converted to ordinary source searches without displacing existing ones

Status: Accepted

## Context

The For You shelves page was removed, but backups and old data still hold saved For You searches (`isRetiredForYouSearch`).

## Decision

- On load and on restore, each is replaced by one search for every source that has usable tags in it, built from that source's tags. A search over several such sources splits, and each part is named "<name> · <source>". Sources without usable tags produce nothing, so an entry with none is dropped.
- Ordinary searches always stay as they are; generated ones give way (dropped when their ID or search already exists). The search for the first source with usable tags keeps the entry's ID and place, unless it was dropped; the others get stable name-based UUIDs and take only free slots below the limit, so re-importing adds nothing. Converting again is a no-op.
- When merging, only an incoming ordinary search replaces the local one with its ID; For You searches are converted together with the existing ones.

## Consequences

The conversion can be removed once no backups with For You searches remain in use.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ForYouSavedSearchConversion.kt`: `withForYouSearchesConverted`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SavedSearchRepository.kt`: `MutableSavedSearchRepository`, `mergedWith`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `RecentSearchEntry.isRetiredForYouSearch`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ForYouSavedSearchConversionTest.kt`: `a saved mix splits into one search per source, named for its source (ADR 0020)`, `a For You search without tags is dropped and other searches are untouched (ADR 0020)`, `an ordinary search wins a collision with a converted one, whichever comes first (ADR 0020)`, `split searches take only free slots, so every existing search stays (ADR 0020)`, `a split search keeps the same generated id forever, so converting again adds nothing (ADR 0020)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/FileBackedSavedSearchRepositoryTest.kt`: `a file the Gson store wrote loads with its For You pin as source searches, rewritten once (ADR 0020)`, `a backup's For You pin is imported as source searches (ADR 0020)`, `a backup's For You pin never replaces the local search with its ID (ADR 0020)`, `an incoming ordinary search replaces the local one with its ID (ADR 0020, 0025)`
