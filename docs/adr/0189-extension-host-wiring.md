# 0189. The app attaches the extension host lazily, keeps extension choices on the device and watches package changes

Status: Accepted

## Context

Extensions are third-party code loaded into the app. Turning one on is a decision made on one device about the code installed on it, so it must not travel with a backup to another phone. The application has no files or receivers ready when the host attaches, and loading extensions at startup would be slow.

## Decision

- `DeviceExtensionChoices` stores trusted extensions, languages, owners and repos in `extension_choices.json` under `noBackupFilesDir`, which Android neither backs up nor transfers to a new phone. A profile backup carries only the repos (`ExtensionRepos.restore` merges them in). The settings store has no place for them: keys that builds before the first release with extensions wrote there are ignored when read and dropped on the next write.
- `ExtensionHost.deleteLegacyPreferences` removes the extension preferences debug builds kept in `shared_prefs`, where Android backs them up. Nothing is carried over, so an extension that was on in such a build is turned on once more. It runs at every start and writes nothing once they are gone.
- `ExtensionSourceHost` loads nothing at startup, and only the extensions the user turned on in the Extensions page ever load (`ExtensionRegistry` holds that rule). Each loads once, the first time it is needed. `attach` runs before the application has files or can register anything, so it only builds lazies: the installed-extensions folder (`noBackupFilesDir/extensions`, never backed up, written only by repos), the image key, and the host's own setup, which happens when the first extension loads.
- Release builds load installed Android packages and the repo-installed APKs in app-private storage (`ExtensionOrigin.PACKAGE` and `PRIVATE`). Only in debug builds, APK files pushed to `getExternalFilesDir(DIRECTORY)` (`mihon-extensions`, `ExtensionOrigin.FILE`) also load, which is how tests hand a debug build an extension. The directory keeps the name from when Mihon's were the only extensions, so files pushed there for tests keep loading. The `FileObserver` for it is held in `folderWatch` so the folder stays watched, and finding the folder can create it, so it runs off the main thread.
- `bind` supplies the choice store once the app's data opens; `choices` is a `CompletableDeferred`, so everything else (the HTTP clients already hold `mediaInterceptor`) waits for it. It also reads the image key file now, off the main thread, since screens tag covers as they draw.
- The first image an extension serves loads that extension on an HTTP client's thread (`runBlocking` inside `ExtensionImages`); after that, images only read the table.
- `watchPackages` notices an extension installed, updated, removed, hidden or unhidden while the app runs: the registry lists the device again, updates the sources that fetch images and, when the extensions changed, moves its revision, which reloads the Series tab. This is the only way the registry learns of changes, so it starts before the extensions first load (the `watching` lazy is read by `sources`, `source`, `availability` and `extensions`). A conflated channel means a burst of changes lists the device once more after the listing under way; one failed listing, such as a binder error, is logged and does not stop later changes. An update also sends REMOVED and ADDED marked as replacing, so only its `ACTION_PACKAGE_REPLACED` is acted on. Debug builds also watch the pushed-APK folder, which sends no broadcast.
- `repos(client)` sends repo requests with `CookieJar.NO_COOKIES`, because repos are no site the user signed in to. An install or removal relists the extensions at once (`onChanged`), since Android announces no change to the app's own folder.

## Consequences

Moving to a new phone means re-enabling extensions and re-trusting their signers. The first use of a source or an extension-only image can take seconds. A new way to install extensions must call `registry.recheck` itself if Android sends no broadcast for it.

## Applies to

- `app/src/main/java/com/theoriacodex/app/di/DeviceExtensionChoices.kt`: `DeviceExtensionChoices`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `deleteLegacyPreferences`
- `app/src/main/java/com/theoriacodex/app/extensions/ExtensionSourceHost.kt`: `ExtensionSourceHost`, `attach`, `bind`, `watchPackages`, `repos`, `DIRECTORY`, `INSTALLED_DIRECTORY`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionPackages.kt`: `ExtensionPackages`, `ExtensionOrigin`

## Guarded by

- `app/src/test/java/com/theoriacodex/app/di/DeviceExtensionChoicesTest.kt`: `nothing is on until it is turned on here and choices are kept in the device file (ADR 0189)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/FilePreferencesTest.kt`: `preferencesLiveWhereAndroidDoesNotBackUpAndOldFilesAreDeletedNotImported_adr_0061_adr_0189`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionRegistryTest.kt`: `an extension that is off is listed without running its code (ADR 0007, 0189)`
