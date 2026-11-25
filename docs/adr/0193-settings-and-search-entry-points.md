# 0193. Settings can be opened from anywhere and every setting has exactly one binding in the search box

Status: Accepted

## Context

Settings is reachable from its own row, from Search's box, from banners and from other screens, sometimes during startup, and each entry must return the user to where they came from. The search box lists every setting definition, but some cannot be opened or changed from there. The settings view model needs the app container, which the feature module cannot name.

## Decision

- `SettingsScreenDestination` remembers the page Settings was opened at (`closesFrom`); Back from that page calls `onClose` and returns to the screen that opened it (Search's "Sites" jump, for example), while Back from any other page goes to Settings' home.
- `openSettings` opens Settings at a page with a sheet over its home, above the current screen. `showSettingsRoute` waits until `navReady` before pushing the route, so a jump during startup waits for it to finish and restoring the last tab cannot cover Settings.
- `settingsViewModelFactory` lives in `:app` because `:feature-settings` cannot name the container; the dependencies are read only when the view model is first created.
- `SETTING_BINDINGS` binds every setting definition id to what opens or changes it, and a test checks that none is missing. `SettingBinding.InSearch` is for a setting Search offers itself, beside the sheet it opens; `SettingBinding.InViewer` is for one set from the Viewer's playback menu, which needs a video open, so the search box has no entry for it. `searchSettingEntries` leaves out definitions this build cannot open (Diagnostics without a report, Extensions and series checks without extensions).
- The Settings page for stats comparison keeps Home as the animated target while the file picker covers it (`choosingStatsFile`), until a file is chosen.
- The Picture tags page explains what tagging does (hidden tags, For You and save suggestions) while the model is not ready, so the user reads it before the model download rather than after.
- The Series updates row shows "notifications off" instead of its interval when notifications are disabled and a check is scheduled, because a check that cannot announce what it finds is silent. `notificationSettingsOpener` reads the permission again on every resume and offers the app's notification settings, since the system stops showing its permission prompt once it was refused and its settings are the only way back.

## Consequences

A new setting definition fails the binding test until it is bound. A new entry point to Settings should go through `openSettings` so that it waits for startup and returns correctly.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/SettingsScreenDestination.kt`: `SettingsScreenDestination`, `openSettings`, `showSettingsRoute`
- `app/src/main/java/com/theoriacodex/app/ui/SettingsViewModelFactory.kt`: `settingsViewModelFactory`
- `app/src/main/java/com/theoriacodex/app/ui/SearchSettingBindings.kt`: `SettingBinding`, `SETTING_BINDINGS`, `searchSettingEntries`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsScreen.kt`: `SettingsScreen`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SeriesSettingsGroup.kt`: `SeriesUpdatesRow`, `notificationSettingsOpener`
- `app/src/main/java/com/theoriacodex/app/settings/PictureTagsEntry.kt`: `rememberPictureTagsEntry`
