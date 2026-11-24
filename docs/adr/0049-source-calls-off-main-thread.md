# 0049. Source entry points run on a background dispatcher through sourceCall

Status: Accepted

## Context

Source adapters are called from UI-facing code, including the main thread. Parsing provider responses and waiting on the network on that thread would stall the app, and the app reports any provider parse that runs there.

## Decision

- Every function on the source boundary is written as `= sourceCall { ... }`, which switches to `Dispatchers.Default`. `SourceEntryPointCompletenessTest` enforces this form.
- A few adapters (Hitomi, nhentai) accept a `callContext` so tests can pass `EmptyCoroutineContext` and keep virtual time. Production never sets it.
- Provider response parsing goes through `parseJsonArray` / `parseHtmlFragment` style helpers that call `OffMainWork.note(OffMainWork.SOURCE_PARSE)`, so main-thread parsing is detected.
- Every source boundary function is written as `= sourceCall {` or `= sourceCall(callContext) {` and ends where that block ends. The boundary is every override of a suspending function in `SourceAdapter.kt`, every public suspending member of the boundary classes (`PixivAuthApi`, `PixivTokenCoordinator`, `SiteProber`, `SiteHealthCheck`) and `fetchGelbooruProfileOwner`.
- Adapters take a constructor parameter `callContext: CoroutineContext = Dispatchers.Default`. Production never sets it; only tests pass `EmptyCoroutineContext`.
- `callContext` may appear only in its declaration, in the `sourceCall` wrapper, and in a subclass handing its own parameter to its base class. A positional argument would choose the dispatcher without naming `callContext`, so the completeness scan rejects it.
- Tests that depend on virtual time or ordering pass `EmptyCoroutineContext`: pacing waits and request timeouts must show on the virtual clock, queued responses must be taken in order, an in-flight load must park its continuation deterministically, and the orchestrator's deadlines and two-at-a-time hydration must stay on the test dispatcher. With the default dispatcher, hydration bookkeeping is shared across threads and needs a lock.
- The main-safety contract test uses a plain single-thread dispatcher as the stand-in main thread, deliberately not a main-dispatcher type, because Compose's dispatcher is not one either.

## Consequences

New adapters get main-thread safety by following one shape. A new entry point that skips `sourceCall` fails the completeness test. Tests that need real dispatchers cannot use `callContext`.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/SourceAdapterCommon.kt`: `sourceCall`, `parseJsonArray`, `parseHtmlFragment`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/hitomi/HitomiSourceAdapter.kt`: `callContext`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt`: `callContext`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt`: `callContext`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/KvsVideoSourceAdapter.kt`: `callContext`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/SourceEntryPointCompletenessTest.kt`: `BOUNDARY_CLASSES`, `BOUNDARY_FUNCTIONS`, `CALL_CONTEXT_DECLARATION`, `ALLOWED_CALL_CONTEXT_USES`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/SourceMainSafetyContractTest.kt`: `SourceMainSafetyContractTest`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruSourceAdapterTest.kt`: hydration bookkeeping lock
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiPaginationSourceAdapterTest.kt`, `nhentai/NhentaiCreatorSourceAdapterTest.kt`, `nhentai/NhentaiSuggestionSourceAdapterTest.kt`, `pixiv/PixivRequestPacingTest.kt`, `rule34/Rule34GroupedSearchMetadataTest.kt`: `callContext = EmptyCoroutineContext`
