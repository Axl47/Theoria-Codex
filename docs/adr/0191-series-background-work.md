# 0191. Series downloads and update checks run as JobScheduler jobs with lane-specific network rules

Status: Accepted

## Context

Chapters the user queued must keep downloading after the app leaves the screen, while chapters the update check queued must not spend metered data. Android lets only an app in view start a user-initiated job, the system can stop any job when the network changes, and a notification posted for an update must not be lost if a run is stopped midway.

## Decision

- `SeriesDownloadScheduler` schedules two persisted jobs. The user's lane is a user-initiated data-transfer job on the networks the download settings allow (unmetered, not roaming, or any); it keeps going once the app leaves the screen and waits for such a network when there is none. The automatic lane (chapters the update check queued) is an ordinary job that requires an unmetered network. Both survive reboots; the manifest declares the service, `RUN_USER_INITIATED_JOBS` and `RECEIVE_BOOT_COMPLETED`.
- Only an app in view may start a user-initiated job. When scheduling one fails (resuming after a restart, say), the user's lane is scheduled as an ordinary job and its chapters still download.
- `SeriesDownloadJobService` drains its own lane. When both jobs run they take turns chapter by chapter, and a chapter in flight when the network changes finishes on the new one. The automatic lane waits (returns "run again") while the default network is metered, even if the system started the job on an unmetered one, since requests go over the default network; for the same reason `onNetworkChanged` does nothing.
- A user-initiated job must name its notification at once, so `showProgress` runs first in `onStartJob`. An ordinary job posts it with `postDownloadProgress` instead.
- A chapter that may download later, or a full disk while the queue is saved, ends the run with a retry, so the system runs the job again after a growing wait. A cancellation from `onStopJob` is rethrown: what is left stays queued for the next run. A run removes only its own entry from `runs`, because a replacement of the job may already be running under the same id. `onStopJob` asks for a retry unless the user stopped it, in which case the lane waits for the next app start.
- `downloadChapterPage` treats a 404 or 410, a response that is not an image, or an undecodable file as `ChapterUnavailable`; any other failure passes through unchanged.
- `SeriesUpdateJobService` is a periodic job on any network (`setPeriodic`, persisted), kept scheduled exactly while a series is followed and the interval setting is on (`keepSeriesUpdatesScheduled`). Scheduling again restarts the wait, so a pending job with the same period is left alone at every app start. It announces each updated series as soon as its chapters are stored, so a run the system stops midway has announced what it stored; a cancelled run is not reported finished a second time. A failure such as saved data that cannot open is reported to diagnostics, since the app's own start shows that failure and the next period tries again.
- `SeriesDependencies.checkFollowed` takes a lock: the Series tab's refresh and the background job both call it, one check runs at a time, so neither repeats the other's requests midway and a chapter one stores is not new to the other.
- Notifications: new-chapter and download notifications share one channel, kept under its first id (`new_chapters`) so the user's choices carry over. Without the notification permission nothing is posted; the new entries are stored either way. The group summary appears once two series have one showing, counting the posted one by name because the system may not list it the moment it is posted, and it names no series: tapping it only brings the app forward.
- `SeriesNotificationActivity` is not exported, so only this app's notifications reach it, and `opened` is the only way to a series from outside the app's own screens: a link another app sends cannot name one. The shell opens each tapped series as a new page, since reusing the one on top would keep the series that page was opened for.

## Consequences

Automatic downloads never run over metered data, even briefly. A stopped user job needs the user to restart the app to resume. Adding a lane means a new `AppJob` entry and manifest wiring.

## Applies to

- `app/src/main/java/com/theoriacodex/app/series/SeriesDownloadJobService.kt`: `downloadChapterPage`, `SeriesDownloadScheduler`, `SeriesDownloadJobService`
- `app/src/main/java/com/theoriacodex/app/series/SeriesUpdateJobService.kt`: `SeriesUpdateJobService`
- `app/src/main/java/com/theoriacodex/app/series/SeriesUpdateSchedule.kt`: `keepSeriesUpdatesScheduled`, `scheduleSeriesUpdates`
- `app/src/main/java/com/theoriacodex/app/series/SeriesNotifications.kt`: `notifySeriesUpdate`, `SeriesNotificationActivity`
- `app/src/main/java/com/theoriacodex/app/di/ExtensionSources.kt`: `SeriesDependencies.checkFollowed`
- `app/src/main/java/com/theoriacodex/app/ui/TheoriaShellEffects.kt`: `ShellIncomingUriEffects`
