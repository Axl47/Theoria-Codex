# 0058. The real-extension device harness reports text only and measures extensions without the app's browser

Status: Accepted

## Context

Provider feeds are poorly moderated and can show sexualized depictions of minors, so provider media must never be shown, saved or described.

## Decision

- Harness tests report counts, types, sizes and timings as text only; they never play, show or save media, and describe a response by its leading bytes rather than content type. They run against the module's own test package with the host installed without the app's WebView, so sites behind a browser check fail as they would before any check is passed (`NoCookies`, `NoBrowser`).
- They are skipped unless an APK is named (`animeApk` / `extensionApk`).
- Covers are fetched all at once as well as singly because a feed asks for every cover on screen together and some sites answer differently.

## Consequences

Harness results cannot establish behavior that depends on the app's browser cookies.

## Applies to

- `extension-host/src/androidTest/kotlin/com/theoriacodex/extensionhost/Harness.kt`: `Harness`, `NoCookies`, `NoBrowser`
- `extension-host/src/androidTest/kotlin/com/theoriacodex/extensionhost/AnimeHarnessTest.kt`: `AnimeHarnessTest`
- `extension-host/src/androidTest/kotlin/com/theoriacodex/extensionhost/ExtensionHarnessTest.kt`: `ExtensionHarnessTest`
