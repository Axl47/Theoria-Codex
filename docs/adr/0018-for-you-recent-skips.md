# 0018. For You leaves out posts skipped in the last 30 days

Status: Accepted

## Context

The deck's record of decided posts lived only in memory, so after a restart For You dealt, re-tagged and re-vetted the cards the user had just skipped. The swipe log (`for_you_verdicts`, up to 20,000 rows per profile) already recorded every skip but was never read. A permanent exclusion would drain small or newest-sorted sources and make one skip final.

## Decision

Before a profile's first deal, the Mix reads that profile's SKIPPED verdicts from the last 30 days and treats those posts as already decided, on the Mix and on trails. The read:

- runs alongside lane planning, after verdict writes already queued, but does not hold the write lock while it queries;
- returns at most the newest 5,000 skips, about 30 days at 150 skips a day;
- is waited on for at most 1.5 s. Only the wait is timed, because a blocking Room query cannot be cancelled. A read that fails or runs late deals without the skips and is tried again by the next Mix.

Refresh starts a fresh Mix that keeps these exclusions. Start over, at the end of the Mix, forgets this session's decisions and the recent skips, and counts the skips as read so the rebuild does not load them back. Liked, saved and hidden posts stay out either way.

## Consequences

Skips survive restarts without becoming permanent, and a small source refills as its skips age past 30 days. Copies of a skipped post on other sites are not excluded by id. Beyond 5,000 skips in 30 days, the oldest of them can be dealt again. Changing the window or the cap only means changing the constants.

## Applies to

- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckViewModel.kt`: `readRecentSkips`, `startOver`, `isKnown`, `SKIP_MEMORY_MS`, `SKIP_READ_LIMIT`, `SKIP_READ_TIMEOUT_MS`
- `core-data-android/src/main/java/com/theoriacodex/data/android/room/ForYouVerdictDao.java`: `since`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/ForYouVerdictRepository.kt`: `skippedSince`
