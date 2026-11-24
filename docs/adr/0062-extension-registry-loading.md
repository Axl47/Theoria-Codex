# 0062. The registry loads extensions lazily, per source lookup, and publishes one consistent offer

Status: Accepted

## Context

Loading extension code is expensive and risky, a cold start asks for many sources at once (a chapter's pages), and loaded code cannot be unloaded.

## Decision

- `ExtensionHost.install` must have run before any extension loads (`ExtensionLoader.load`), since loaded code reaches the host through it.
- Nothing loads at startup or before first asked. A lookup of one source loads the extension last seen offering it (`ExtensionChoices.owners`, a hint persisted without cancellation so what loaded is published with it; failing to store it only means the next start loads everything). Only an unknown source, or one missing from its owner, loads every extension that is on. Source ids survive extension renames, so an owner gone from the device settles nothing, and an owner that is on the device but off, or in a language that is off, answers null at once.
- Concurrent cold-start lookups serialize in `locked` (mutex, off the caller's thread): only the first lists the device, even if reading choices fails (package changes retry).
- Loaded entries are keyed by the whole listed package, so a file replaced in place loads anew. Entries no longer on the device are dropped. Turning an extension on clears a failed load to retry. Trust is recorded as identity to signer, only for the signer the user was shown. A replaced file is a new entry, so its code loads anew; if it has another signer, trust no longer matches and the extension is listed as off until the user turns it on again.
- The sources on offer change only on a listing: user choice, the Extensions page, or `recheck` (called when device packages may have changed). Offers are replaced after a listing completes (a cancelled one leaves the previous), reuse the same list object when unchanged, and move `revision` only for device changes or changes to a complete offer.
- No settings are read during refresh because it runs on first source lookup and image request.
- The first listed copy wins when a file duplicates an installed package: its sources are offered once. Images are published as one complete table of sources whose extension finished loading in a language that is on. Edits to a source's settings look up the sources that are on now, so an edit queued before turning its extension off does nothing.
- Trust is recorded as identity to signer only when the user turns an extension on. A package replaced at the same path with the same signer reloads its code under the existing trust and is not asked about again; `setOn` accepts only the signer the user was shown. An empty fingerprint in the store trusts nothing.
- `ExtensionChoices.owners` remembers which extension last offered each source. A first start knows no owners, so a lookup loads every extension that is on and learns them; a lookup afterwards loads only the owner. Renamed extensions keep their source ids.
- Learning owners for the whole list changes nothing anyone was handed, so the registry revision does not change.
- Once everything that is on has loaded, a source no extension offers is answered without listing the device again.

## Consequences

An unloaded extension makes no request, so `sites()` includes only loaded sources. Turning an extension off leaves its code idle in memory until the process ends.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionRegistry.kt`: `ExtensionRegistry`, `source`, `ownerSettles`, `refresh`, `recheck`, `locked`, `Offer`, `loaded`, `active`, `wanted`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionRegistry.kt`: `ExtensionRegistry`, `setOn`, `source`, `ExtensionChoices.owners`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionLoader.kt`: `ExtensionLoader.load`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt`: `ExtensionHost.install`

## Guarded by

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionLoader.kt`: `class ExtensionLoader private constructor(private val context: Context, private val read: (File) -> ExtensionPackage?)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionLoaderTest.kt`: `the only way to get a loader installs the host first (ADR 0062)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionRegistryTest.kt`: `a failure keeping which extension offers each source fails no lookup, and the next start loads everything (ADR 0062)`, `simultaneous cold lookups list the device and load the extension once (ADR 0062)`, `a lookup loads only the extension that last offered its source (ADR 0007, 0062)`, `a source its recorded extension no longer offers is found by loading every extension, once (ADR 0062)`, `a source whose recorded extension is off is not found, and the others do not load for it (ADR 0062)`, `a source whose recorded extension has its language off is not found, and the others do not load for it (ADR 0062)`, `a source whose recorded extension left the device is found under the one that offers it now (ADR 0062)`, `an extension that is off, or a language that is, is told apart from a source no extension offers (ADR 0062)`, `an anime source's lookup also loads only the extension that last offered it (ADR 0062)`, `a lookup cancelled while it keeps the owners still offers what it loaded (ADR 0062)`, `a file replaced in place loads anew and its old code is no longer offered (ADR 0062, 0063)`, `a file replaced while the user was asked is not turned on (ADR 0007, 0062)`, `an update signed with the same key stays on and a copy signed with another key is off (ADR 0062)`, `a recheck tries again a first load that failed (ADR 0062)`, `a recheck after single lookups lists the device again (ADR 0062)`, `an extension removed, replaced or installed later is picked up by the next recheck (ADR 0062)`, `another extension put under a folder file's name with the same key is not the one asked about or turned on (ADR 0062, 0063)`, `an extension that is off moves the revision when it is installed or removed, for the page that lists it (ADR 0062)`, `another app updating leaves the revision where it was (ADR 0062)`, `settings are built only for the extension asked about and an edit for one turned off does nothing (ADR 0062)`, `the same extension on the device twice offers its sources once (ADR 0062)`, `a source fetches images only while its extension and language are on (ADR 0059, 0062)`
- Left to prose: the pin keeps the constructor private and the factory test shows `installingHost` installs the host, but neither would see a second factory or constructor added beside it; reusing the same list object when an offer is unchanged is an allocation detail; an unloaded extension making no request and turned-off code staying idle in memory are consequences of lazy loading.
