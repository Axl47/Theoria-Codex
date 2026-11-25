# 0221. Series opens Settings, and Extensions leads Settings with its alerts

Status: Accepted

## Context

Settings used to be the rightmost tab. Once it moved behind Library's gear, Series, now the rightmost tab, had an
Extensions button in that corner instead, and people reaching for Settings from Series found Extensions. Moving
Extensions beside Browse's Filter button, with Popular and Latest folded into the Filter sheet, was considered and
rejected: switching Popular and Latest is a repeated action that persistent space is reserved for
(`docs/product-constraints.md`), and the button's badge would show only on Browse while the series it counts live in
Following.

## Decision

- Series' top-right button is a Settings gear that opens the same Settings route as Library's gear. Its badge counts
  what needs attention in Extensions: followed series whose extension is missing, plus repo extensions with an update.
  TalkBack hears each count as a line rather than the badge number.
- Extensions is the first row of Settings, above the recommendation profile, in a group of its own. When either count
  is above zero, the row lists each one that applies on its own line and carries the combined badge; otherwise it is a
  bare label.
- One flow (`observeExtensionAlerts`) produces both counts, and the series container shares it, so Series and Settings
  always agree. Updates come from the repo listing the Extensions page reads (`ExtensionRepos.available`), which
  fetches each repo's index at most once per process; the counts are taken again whenever the installed extensions
  change. No separate update check exists.

## Consequences

Settings is one tap from Series; Extensions is two (Settings, then Extensions). Opening Series now also reads the
repo indexes once per process, which the Extensions page used to do on first open. A badge that should also count
something else (failed checks, broken sources) belongs in `ExtensionAlerts`, not in a second badge.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/series/ExtensionAlerts.kt`: `ExtensionAlerts`, `missingExtensionsLine`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/MissingExtensions.kt`: `observeExtensionAlerts`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SeriesTabScreen.kt`: `SettingsButton`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroup.kt`: `ExtensionsGroup`
- `app/src/main/java/com/theoriacodex/app/di/ExtensionSources.kt`: `SeriesDependencies.extensionAlerts`
- `app/src/main/java/com/theoriacodex/app/series/SeriesDestinations.kt`: `SeriesTabDestination`, `openSettings`

## Guarded by

- `feature-series/src/test/kotlin/com/theoriacodex/app/series/MissingExtensionsTest.kt`: `extension alerts add series missing their extension to extensions with an update, and recount when extensions change (ADR 0221)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/SeriesTabScreenTest.kt`: `the Series settings button opens Settings and badges extension alerts (ADR 0221)`
- `feature-settings/src/test/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroupTest.kt`: `extensions sit above everything else and list each alert on its own line (ADR 0221)`
