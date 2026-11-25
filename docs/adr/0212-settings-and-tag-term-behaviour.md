# 0212. Settings navigation, the Series sheet, and how post tag chips are built

Status: Accepted

## Context

These decisions were recorded as code comments in `SettingsScreen` and `PostTagActionSection` until #386 moved
`SettingsScreen` into feature-settings and split the tag-term code out into a new `PostTagActionTerms.kt`. The comment
ratchet gave the moved and the new file no allowance, so the comments were deleted rather than carried. Each is restated here and was
checked against the code it describes.

## Decision

Settings:

- The Settings home shows each setting's current value on its row, where it has one. A short choice opens a sheet over the home; a larger
  task opens its own page.
- Settings can be opened at any page (`closesFrom`). Back on that page closes Settings; Back on any other page returns
  to the Settings home. When Settings was opened at Compare, closing the comparison closes Settings too.
- Settings that only apply to Series (how often followed series are checked, and Delete after reading) sit in their own
  Series group right after Sources, so Sources holds only what applies to every source. The Series group appears only
  in a build with extensions, where the app has a series container. Extensions sits above every group instead
  (ADR 0221).
- The Series updates sheet holds a single choice, so choosing an interval closes it. Its Notification settings button
  leaves the sheet open.
- While a series check is scheduled but the app may not post notifications, the Series updates row reads
  "Notifications off" instead of the interval. While the app may not notify, the sheet offers the app's notification
  settings, whatever the interval: once the permission is refused repeatedly, Android stops showing its prompt, and
  the system settings are the only way back. Whether the app may notify is read again every time Settings resumes, so
  returning from the system settings updates the row.
- Compare opens the system file picker once each time the Compare page is entered without a file, with the Settings
  home kept behind the picker. A readable stats file opens the comparison; a cancelled pick or an unreadable file goes
  back as Back would.
- The Storage page shows its own cache controls only when the library transfer tools, which include cache controls,
  are absent.

Post tag chips:

- Tag groups are ordered artist, series, character, tag, group, type, language: the most identifying types first,
  since they make the most useful searches.
- A tag with a provider translation shows `translation · native` on its chip, and the chip still searches the native
  tag.
- Counts a provider supplies are keyed by the same normalized `SearchTerm` (trimmed value, blank namespace dropped) as
  the chips, so each count finds its chip.

## Consequences

Builds without extensions show no Series or Extensions rows and need no series dependencies in feature-settings.
Adding a second choice to the Series updates sheet means it should stop closing on choice. Changing the tag-term
normalization means changing it for both the chips and the supplied counts.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsScreen.kt`: `SettingsScreen`,
  `SettingsSourcesGroup`, `StatsFilePicker`, `FallbackCacheControls`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroup.kt`: `SeriesSettingsGroup`,
  `SeriesUpdatesRow`, `notificationSettingsOpener`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/DownloadPreferencesSection.kt`: `SeriesUpdatesSection`
- `app/src/main/java/com/theoriacodex/app/ui/routes/DestinationStateBoundaries.kt`: `showSeries`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/PostTagActionTerms.kt`: `postTagFacetOrder`, `tagTranslations`,
  `postTagChipLabel`, `postActionSuppliedCounts`

## Guarded by

- `feature-settings/src/test/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroupTest.kt`: `series settings sit in their own group after sources (ADR 0212)`
- `feature-settings/src/test/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroupTest.kt`: `without series the group and its rows are absent (ADR 0212)`
- Left to prose: the notification-permission row text, sheet closing, Compare picker and tag-chip rules have no test yet.
