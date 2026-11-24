# 0186. Startup opens every durable store and registers restored work in a fixed order before any screen runs

Status: Accepted

## Context

The app container builds the stores, source registry and download queues, and restored work (downloads, offline jobs, scheduling) depends on them. A store that cannot open must reach the startup failure screen, not crash a collector. Requests that race an unready cookie bridge are challenged and record a ten-minute outage. Restored jobs resolve posts through the source registry, which needs account availability and custom-site adapters first.

## Decision

- `awaitDurableStores` completes typed-store migration before any route can observe default placeholder state. Every DataStore is awaited there: one that cannot open throws, which shows the startup failure screen, while collectors started during construction wait instead of crashing. Retry calls it again on the same container.
- Within it: `siteProfiles.awaitReady()` and the cookie bridge's site hosts and built-in agents are set before the app is ready, because a request racing an empty host list would be challenged and trip a 10-minute outage (`HostOutageTracker.DEFAULT_OUTAGE_MS`). The init block keeps the bridge's allowed hosts equal to the saved sites; `awaitDurableStores` seeds them first. The old `browser_verified_sites` preferences are deleted since allowed hosts now come from the saved sites.
- The offline catalog scan (`offlineStore.load()`) is independent of the profile stores, but its snapshot must be warm before jobs resume. `offlineMedia.recoverPendingJobs()` registers the saved offline queue before any screen can add to it; its jobs wait on `sourceReadiness`. `sourceReadiness.open()` is called once, at the end of the store awaits.
- `SourceReadiness.open()` loads account availability (the same refresh the shell runs on startup), so sources that need a saved key, such as Rule34.xxx, are in the registry. A failed read still opens; the shell retries it. `await()` also waits until custom sites are built into adapters (`CustomSourceRuntime.awaitFirstBuild`).
- `appPostDownloads` builds the app's one `PostDownloads` over its job file; each container calls it once, so every Activity shares it. Posts resolve through the source registry, Pixiv animations export through its Ugoira client, and downloads start only after `sourcesReady`.
- Followed-series watching opens Room, so it starts only after the stores are awaited, which gives Room the chance to fail into the startup screen (`seriesScheduling`). The saved-search repository is opened before anything starts background work: a pins file from a newer build fails construction, and a retry builds a new container while the failed one's stores would still be open.
- The series dependencies are created before the backup service, which adds an imported backup's extension repos. The series download queue file is under `noBackupFilesDir`, and offline copies live outside Android backup and device transfer, so neither is carried to a new phone. Chapter downloads fetch through `httpClients.downloads`, which carries the extension interceptor, as the Viewer's image loader does.
- Container fields that are shared on purpose: `outages` (`HostOutageTracker`) is one tracker for the built-in and custom-site clients; `healthCheck` shares the app's client so it carries browser-verified cookies; `postResolutions` keeps one in-flight detail request per post, shared by the feed and the Viewer; `applicationScope` lives as long as the process, for work that must finish after the screen or Activity that started it; `ListingPictures.annotate` waits at most `budgetMs` for a page's picture hashes and teaches the file-mirror index each copy before listings collapse them, so a copy a listing shows once stays reachable through "Find on other sites".
- When the picture model is deleted, the For You tag affinity drops its picture evidence at once (`pictures.tagger.ready`), before the shared inputs drop it, so lanes are planned again immediately.
- Custom-site requests use a 20 s read timeout (`CUSTOM_SITE_READ_TIMEOUT_MS`) matching the built-in client's, while probes keep the custom client's 10 s default.

## Consequences

Anything that touches a source or the cookie bridge during startup has to come after the step that readies it. A new store must be awaited in `awaitDurableStores` or its failure will not reach the startup screen. Moving the offline or download queues into Android backup would let a restore bring back queues for files that are not there.

## Applies to

- `app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt`: `awaitDurableStores`, `sourceReadiness`, `savedSearches`, `series`, `seriesScheduling`, `applicationScope`, `outages`, `healthCheck`, `postResolutions`, `CUSTOM_SITE_READ_TIMEOUT_MS`
- `app/src/main/java/com/theoriacodex/app/di/SourceReadiness.kt`: `SourceReadiness`
- `app/src/main/java/com/theoriacodex/app/di/PostDownloadsWiring.kt`: `appPostDownloads`
- `app/src/main/java/com/theoriacodex/app/di/ListingPictures.kt`: `ListingPictures`
