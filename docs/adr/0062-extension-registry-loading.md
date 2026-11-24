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
