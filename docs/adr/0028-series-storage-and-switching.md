# 0028. Series entry lists, progress and source switches are stored conservatively

Status: Accepted

## Context

Series come from extension sources that fail quietly, reorder, rename or re-address chapters, and can be replaced by another source for the same series.

## Decision

- Storing an entry list keeps user progress, first-seen time and memo; dropped entries are remembered so a returning one is as the user left it. An empty list changes nothing. A new address counts as the dropped entry moved only when both are the only release of that chapter number by their translator group; a first list or one where nothing matches becomes the baseline with no new entries.
- Page addresses are never stored because they expire. `chaptersShareFirstPage` stays set once set and is not carried across sources.
- `position`/`length` use one pair for both kinds: a page (from 1) for manga, milliseconds for anime. `hasProgress` matches `SeriesDao.ENTRY_HAS_PROGRESS`.
- Refreshes of one source share a lock so two refreshes cannot store lists in the other order they were fetched. The same lock covers a switch of source. Followed checks run sources side by side but one series at a time per source (one rate limit), store with `NonCancellable` so a stopped check has reported what it stored, and fetch entry lists only because details may be older than what a series page stored meanwhile.
- A source switch carries settings and progress by `carryProgress`. Each old entry goes to the new entry with its chapter number, preferring one of the same release (same translator group, or both none) and otherwise the first in reading order; failing that, to the entry with the same name, only when that name is unique on both sides (names such as "Extra" repeat) and at least one of the two entries has no chapter number. A new entry takes read, the further place (capped at a known length) and the later read time from the old entries sent to it; when some of them are its own release, only those count, so another release that fell back to it says nothing about it. Only same-kind series switch; the target's own progress is never reduced; an empty target list is refused when progress would be lost. Followed time is the earlier of the two.
- Continue resumes at the first that exists: the entry opened last, while unread; the entry immediately after it, while unread; the last entry in reading order the user is partway through (past its first page or first moment); the first unread entry; then, with everything read, the entry opened last, or the last entry in reading order when none was opened or it is no longer listed. Episodes resume where left unless that is in the tail, the shorter of `RESUME_TAIL_MS` (two minutes) and 10% of the duration, where they restart; finishing at 85% still marks watched.
- Read status inferred by a switch applies only to manga (episodes are watched one by one). A chapter no old entry went to is read when the old series had another release of its number, or one of its unique name, that was read, or when it is numbered below the chapter opened last on the old series (a source that skips chapters is still read past them). Continue on the new series goes on from the match of that entry, else the furthest entry the switch marks read; the target's own opened entry stays when it is as far or further.
- On a switch, chapters not listed any more (negative order) still count their place in the old series, and a release with no match for its own group falls back to the first release of that number in reading order.
- A restored database lists every chapter but nothing is on the device; files a profile restore finds show as downloaded without a queue or database record.
- Only the user's download job follows the network settings. A sources change leaves a draining lane its running job; a network change replaces it. The system may start the replacing job before the replaced one has stopped, and a lane that is still draining makes a newly queued chapter join the running drain instead of replacing its job.

## Consequences

Any new per-series setting must be added to `withSettingsOf`. Matching rules change what a switch marks read.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesRepository.kt`: `store`, `hasProgress`, `withProgress`, `continueEntry`, `resumePositionMs`, `episodeStartsOver`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesSwitch.kt`: `switchSource`, `withSettingsOf`, `carryProgress`, `EntryMatcher`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SeriesUpdates.kt`: `refreshLocks`, `refresh`, `checkFollowed`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SeriesProgressTest.kt`: `continueEntry`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SeriesSwitchTest.kt`: source switch carry-over
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SeriesDownloadsTest.kt`: `SeriesDownloads.drain`
