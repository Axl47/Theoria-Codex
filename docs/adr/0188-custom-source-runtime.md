# 0188. User-added sites are rebuilt into adapters on every profile or key change and published presentation-first

Status: Accepted

## Context

Custom sites can be added, edited and removed while the app runs, and adapters hold a snapshot of their keys. Callers look up a source's adapter and its presentation (label, referer, icon) separately, so an available source must never be seen without a presentation.

## Decision

- `CustomSourceRuntime` rebuilds all adapters whenever stored profiles or custom-site keys change, without an app restart. Adapters hold a key snapshot, so a key edit takes effect through the rebuild. Each rebuild has a new `revision`; `awaitFirstBuild` returns once one has been published.
- `publish` installs new presentations (together with the previous ones) before the snapshot and its adapters are published, and withdraws removed ones only after. Any available source therefore always has a presentation.
- A site's presentation uses the profile's label; its Referer and User-Agent overrides apply to media requests.
- `CompositeSourceAdapterRegistry` exposes built-in sources plus custom sites. A built-in is available only when the build exposes it (`potentialBuiltIns`) and the account store's `availableBuiltIns` allows it; the account store can name a source this build does not expose, so both must agree. A custom site is available while its snapshot entry has an adapter (a known family, and keys stored when the site requires them).
- Capability changes keep every adapter and orchestrator instance. `unifiedOrchestrator()` caches one orchestrator per custom revision; callers fetch it per search, so changes apply to the next search. Every page any adapter lists passes `annotateListing` (picture hashes), whichever kind of source it came from.
- Callers filter the orchestrator by their own source set, since it holds every exposed built-in, available or not.

## Consequences

A key edit costs a full adapter rebuild and a new revision. A search already running keeps the orchestrator it started with. Anything that reads a presentation must tolerate it outliving its source by one publish.

## Applies to

- `app/src/main/java/com/theoriacodex/app/di/CustomSourceRuntime.kt`: `CustomSourceRuntime`, `publish`, `awaitFirstBuild`, `CustomSiteState`, `CustomSourceSnapshot`
- `app/src/main/java/com/theoriacodex/app/di/CompositeSourceAdapterRegistry.kt`: `CompositeSourceAdapterRegistry`, `unifiedOrchestrator`
