# 0156. Settings state lives in an activity-retained owner, with statistics collected only while shown

Status: Accepted

## Context

Settings pages and sheets can be opened from elsewhere (the search box, other screens), and some data behind them is expensive or comes from the system.

## Decision

- `SettingsViewModel` is activity-retained and owns Settings rendering, mutations, recovery and durable expansion state. The current `SettingsPage` and `SettingsSheet` are held by the owner so other screens and the search box can open Settings at a page; opening any page closes an open sheet.
- Library and usage statistics are collected only while a screen subscribes, because the projection re-reads every saved post whenever the library changes.
- A diagnostics report is shown only when it was built for the current choices and generation. While a rebuild runs, the page keeps the previous report internally but hides it: the preview reads "Preparing report…" and Share and Download are disabled until the new one is ready.
- The Open links row re-reads the system's link-handling state each time the user returns (`LifecycleResumeEffect`), because the choice is made in system settings. Sites cannot verify a third-party app, so Android only sends their links to the app once the user allows it; per-site choices survive Android's overall switch but it overrides them while off, so a disabled overall switch counts zero allowed hosts.
- Stats: one post opens at a time (`rememberPostOpener`), because a source lookup can take a moment and a second tap would stack Viewers.

## Consequences

Anything that needs a Settings page from outside must go through the owner's page state rather than navigating directly.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsViewModel.kt`: `SettingsViewModel`, `statistics`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsState.kt`: `SettingsPage`, `SettingsSheet`, `OpenPage`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/DiagnosticsPage.kt`: `DiagnosticsPage`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/OpenLinksRow.kt`: `OpenLinksRow`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/StatsScreen.kt`: `rememberPostOpener`
