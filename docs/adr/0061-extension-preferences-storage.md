# 0061. Extension preferences live in no-backup JSON files, written before apply returns

Status: Accepted

## Context

Extensions store what the user types into their settings, which for a self-hosted server is address, username and password. Android backs up every file in `shared_prefs` unless a rule names it, and platform `SharedPreferences` cannot be pointed elsewhere.

## Decision

- Preferences are `FilePreferences` JSON files under `noBackupFilesDir`, which Android neither backs up nor transfers. The directory keeps the name `mihon_preferences` since renaming it would lose every source's stored settings. Legacy debug `shared_prefs/mihon_<name>.xml` files are deleted at startup, nothing imported.
- A source asks for `source_<id>` and can never name an app file. This keeps names apart; it is no barrier against code in the same process. A manga and an anime source with the same id share one file because the host cannot tell which asked.
- Values in memory are what readers see, even when a write fails; `apply` writes before returning on the caller's thread (files are a few hundred bytes and extensions change them off the main thread); the next commit writes even if it changes nothing after a failed write; listeners run on the main thread and a failing listener is reported, not fatal. `SourceSettings.change` uses `commit` so the value is on disk when it returns.
- A corrupt file is set aside as `.corrupt` rather than replaced unseen; unreadable entries are skipped. Each value is stored under a one-letter type key.
- `SourceSettings` builds the screen anew each call so computed summaries are current; "%s" in a list summary stands for the chosen entry.
- `ExtensionHost.preferencesFile` places each file under `noBackupFilesDir`, with the extension-chosen name URL-encoded so it cannot leave the directory (`../../escape` stays inside).
- `deleteLegacyPreferences` removes the `mihon_*.xml` files debug builds wrote in `shared_prefs`; nothing is imported.
- `FilePreferences` posts change notifications to the main thread, not the writing thread. Memory can be ahead of the file, so a commit that changes nothing still writes and reports it did.
- Attaching a preference to the store (`Preference.attach`) persists its default so the source's own read finds it, but only when the key is non-empty, the store holds no value for that key, and the default is of a type the preference stores (a boolean, a string or a set of strings for the matching preference kinds).

## Consequences

Settings are not restored from backups. Making writes asynchronous would need a flush point for `commit`.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/FilePreferences.kt`: `FilePreferences`, `commit`, `read`, `encode`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `ExtensionApplication`, `preferencesFile`, `deleteLegacyPreferences`, `PREFERENCES_DIRECTORY`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/SourceSettings.kt`: `SettingsHook`, `SourceSettings`, `SourceSettings.change`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `preferencesFile`, `deleteLegacyPreferences`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/FilePreferences.kt`: `FilePreferences`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/SourceSettings.kt`: settings screen building
- `extension-host/src/main/kotlin/androidx/preference/Preferences.kt`: `Preference.attach`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionHostTest.kt`: `extensions get the app's own application and context, except that every preferences name is a no-backup file of the host's (ADR 0057, 0061)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/FilePreferencesTest.kt`: `preferencesLiveWhereAndroidDoesNotBackUpAndOldFilesAreDeletedNotImported_adr_0061_adr_0189`, `aFileWrittenByAnEarlierBuildIsReadAndValuesAreWrittenUnderTheSameOneLetterTypes_adr_0061`, `anEditWhoseWriteFailsIsStillVisibleAndCommitSaysSo_adr_0061`, `valuesOfEveryTypeAreKeptAcrossReopeningAndChangesAreAnnouncedOnTheMainThread_adr_0061`, `aListenerThatFailsToLinkIsReportedAndTheOthersStillHear_adr_0061`, `oneUnreadableEntryIsDroppedAndAnUnreadableFileIsSetAside_adr_0061`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/SourceSettingsTest.kt`: `a default is stored only for a named key the store lacks, and only of the preference's own type (ADR 0061)`, `a list offers its entries and its summary names the chosen one (ADR 0061)`, `a summary the source works out is worked out again for each listing (ADR 0061)`
- Left to prose: legacy preferences being deleted at every start is not yet guarded, testable in `:app`; `SourceSettings.change` using `commit` cannot be told apart from `apply`, which also writes before returning; a manga and an anime source with the same id sharing one file is a known limitation.
