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
