# 0041. Series notifications: one frozen channel, per-series tags, and a private trampoline

Status: Accepted

## Context

New-chapter and download notifications live outside the app. The user can configure them, the system decides when they're listed, and other apps can send intents. Each of those makes the obvious implementation wrong in a way no screen shows.

## Decision

- **One channel under its first id.** Manga and anime share one new-chapters channel, `NEW_CHAPTERS_CHANNEL`. Its id never changes, because Android keys the user's sound, importance and block choices to the id, and a renamed channel silently resets them.
- **One notification per series.** Every new-chapters notification uses the same id, with its series as the tag, so a later one for the same series replaces the earlier one. The group summary appears once two series have one showing. When counting what's showing, the just-posted notification is included by name, because `activeNotifications` may not list it yet. The summary names no series; tapping it only brings the app forward.
- **Read or Watch only when the notification stands for one entry alone.** A notification that replaces another may stand for an unread entry the earlier one announced, so the entry action is left off in that case. Read leaves the notification up; only a body tap cancels it.
- **A private trampoline.** Every notification opens `SeriesNotificationActivity`, which is not exported. It's the only way into a series from outside the app's own screens, so a link sent by another app can't name a series or an entry. Keep `exported="false"`.
- **The routed entry opens once, tied to its page.** The series page opens a notification's entry from a `LaunchedEffect` keyed to that entry. Back while the entry loads cancels the launch, so the Viewer never opens over another screen. A flag marks the entry as opened, so returning to the page doesn't open it again.
- **Download notices.** The download queue has one ongoing notification, and a failed drain's summary replaces the last one. Chapters waiting for their extension get one notice per lane, under the lane's tag, which clears once that lane has nothing queued. A notice whose chapters share one series opens that series; across series, it brings the app forward.

## Consequences

Renaming the channel, exporting the trampoline, or keying notifications by entry instead of by series would each change behaviour that users see but tests don't fully pin. Revisit only alongside a deliberate channel migration or a deep-link design.

## Applies to

- `app/src/main/java/com/theoriacodex/app/series/SeriesNotifications.kt`: `NEW_CHAPTERS_CHANNEL`, `notifySeriesUpdate`, `SeriesNotificationActivity`, `seriesUri`, `seriesLinkOfUri`, the download and waiting notices
- `app/src/main/java/com/theoriacodex/app/series/SeriesDestinations.kt`: the series page's routed-entry effect
- `app/src/main/AndroidManifest.xml`: `SeriesNotificationActivity` (`exported="false"`)
