# 0192. Series open in the Viewer as sessions whose progress is written in app scope, in order

Status: Accepted

## Context

A series is read or watched through the same Viewer as posts, but it has its own progress, its own reading mode and extension-served media whose addresses expire. The Viewer can close, and its navigation entry disappear, while a last report is still on its way. Extension messages can contain a chapter's path or title.

## Decision

- `SeriesReader.session` builds a session over the series' stored entries in reading order, opening at the requested entry. A chapter opens on `pageIndex` or where the user left it; an episode starts where the user left it (a playback position), so `pageIndex` means nothing for an anime. It returns null when the series or entry is not stored. Posts arrive with their media when the Viewer reaches them, so the Viewer's own start position, next-post swipe and end card give resume and next entry.
- No extension loads for a session. The cover shows while an entry's media loads; one only an extension's own client can fetch is tagged for it (`SeriesCovers`), and that extension loads when the cover is requested. A downloaded chapter's cover is tagged the same way, so offline the cover fails and the pages still read.
- A series session's launch context is never saved: the saved launch context never names a series session (`SeriesPageDestination` opens chapters as series sessions).
- `resolve` asks the source again on every call, because media addresses expire; that is also how the Viewer recovers an episode whose stream stopped working. Media of the wrong shape for the series' kind, or an episode with no stream that can play, is a failure the Viewer shows. A downloaded chapter's pages are its files, so it reads with no connection and without its extension. The source lookup is logged without naming the entry, so a lookup can be timed against playback: the next episode's starts before the current one ends.
- Failures are logged by kind only (`failureKinds`, up to `MAX_CAUSES` causes): an extension's own messages can hold a chapter's path or title, which the log must not keep.
- What the Viewer reports back goes to the series, never to History or a Codex. A chapter whose pages have not arrived shows only its cover, which is not a page read; the Viewer reports it again once they have. An episode's one media item is not a page: it showing makes the episode the series' last opened one with no length, so where it was left stays, and playback is recorded by `recordPlaybackProgress`.
- `record` writes progress in `series.scope` (the app's scope), so a report the closing Viewer sends still lands after its navigation entry is gone, under the `progressWrites` mutex so writes happen one at a time in the order reported: a later position never loses to an earlier one.
- Once a chapter is being read its first page has been shown, so `ChapterPictures.compare` reads the copy the Viewer fetched instead of asking the source again; any copy counts, even one another collection owns. Its `hashPage` reads only files and the disk cache, so comparing never adds a request to the source. Two chapters that open on the same page, such as a group's credit page, would look alike, so once any two of a series' chapters do, each is pictured by its second page (`StoredSeries.chapterLeadPage`). The series stores that (`chaptersShareFirstPage`), so a Viewer opened later, even in a new process, pictures them so from its first frame. A page that does not hash, or whose match could not be saved, is tried again the next time its chapter is offered; nothing is compared for a series that is not stored or already shares its first pages.
- `SeriesReader.opened`/`closed` count open Viewers. Chapters that Delete after reading removes are deleted only once none is reading, never while a reader is open. `rememberSeriesViewerConfig` registers one reader per Viewer owner through a keyed closeable, so it counts as one however often it composes. In a series session every chapter reads in the series' mode, the strip numbers its chapters, and none of the collecting controls show; the Viewer's owner holds the session and the retained one is claimed before this destination composes.
- `seriesQueryHash` keys a series' Viewer stream as `series:<source id>:<address>`; `seriesIdOf` splits it at the first colon after the prefix, which relies on a source id never containing `:`.
- `SeriesDependencies.switchSource` moves a stored series with its settings and progress and deletes the downloaded chapters of the old series, since the moved series no longer needs them. `SeriesDependencies.scope` lives as long as the app, for work that must finish after the screen that started it. `SeriesPageDestination` removes the old series' page from the back stack on a switch, only while that page is still the one on screen.
- `handleViewerEffect` drops save, like, tag and download effects (`collectsPost`) for a session with a series, whichever control asked.
- `recordSeriesProgress` records a series session's progress on `SeriesReader` and reports true, so History and statistics never see it. This holds for both kinds of entry: `recordPlaybackProgress` reports an episode's position, and `recordSeriesPage` reports a visible chapter page (`SeriesReader.recordPageProgress`). The series writes in the app's scope, so the closing Viewer's last report outlives it. `ViewerRouteWorkflow` takes `series` as optional, and a `ViewerStreamSource.SERIES` session leaves no trace anywhere else.
- `recoverMedia` for a series session asks `SeriesReader.resolve` for fresh addresses (a chapter's pages, or an episode's streams) from the series the launch `queryHash` names, because those addresses expire within minutes. A result that no longer applies (`stillApplies`) is dropped before anything records it. Other sources recover through the registry and persist the resolved post. An expired series link therefore comes back through the Viewer's media recovery, not through a lookup of its own.
- A series entry's duration is the player's: the Viewer does not run `ViewerDurationSync` for it, so it is never probed at its stream nor cached as a post's, and `rememberViewerScreenHost` passes no duration owner for a series session.

## Consequences

Progress writes cannot be cancelled by closing the Viewer, but they queue behind each other. A source that changes how it addresses media is picked up on the next `resolve` with no stored state to migrate. Anything that adds a character to source ids must revisit the query-hash format.

## Applies to

- `app/src/main/java/com/theoriacodex/app/series/SeriesReader.kt`: `SeriesReader`, `session`, `resolve`, `record`, `failureKinds`, `seriesQueryHash`, `seriesIdOf`, `opened`, `closed`
- `app/src/main/java/com/theoriacodex/app/series/ChapterPictures.kt`: `ChapterPictures`, `compare`, `chapterLeadPage`
- `app/src/main/java/com/theoriacodex/app/series/SeriesDestinations.kt`: `SeriesPageDestination`
- `app/src/main/java/com/theoriacodex/app/di/ExtensionSources.kt`: `SeriesDependencies`, `switchSource`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaNavDestinations.kt`: `rememberSeriesViewerConfig`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRoute.kt`: `handleViewerEffect`, `collectsPost`, `ViewerRoute`, `rememberViewerScreenHost`
- `app/src/main/java/com/theoriacodex/app/ui/routes/ViewerRouteWorkflow.kt`: `recoverMedia`, `recordPlaybackProgress`, `recordSeriesPage`, `recordSeriesProgress`
