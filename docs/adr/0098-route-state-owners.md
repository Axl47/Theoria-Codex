# 0098. Route owners publish immutable state and keep large or Android objects out of saved state

Status: Accepted

## Context

Navigation-scoped owners are recreated with the process, and `SavedStateHandle` can become a second persistence store if left unchecked.

## Decision

- A `RouteStateOwner` publishes one immutable state stream and accepts typed intent. One-shot work goes through a buffered `Channel` exposed as `effects`, never sticky state.
- Route owners may save compact reconstruction inputs in `SavedStateHandle`. Large result lists, Android handles, players, decoders, launchers and repository or service instances stay out.
- The application container supplies factories and long-lived engines and never retains a route owner or ViewModel.
- `AppRouteSavedStateKeys` lists only keys that cross the app-shell/navigation boundary; feature-private keys stay beside their owner. The list is kept small so process restoration policy stays visible.
- `MutationFeedback` notices stay with their retained command owner until the UI finishes displaying them.

## Consequences

Adding a shared saved-state key is a deliberate act. A new owner that needs large state must rebuild it from the compact inputs.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/state/RouteStateOwner.kt`: `RouteStateOwner`, `AppRouteSavedStateKeys`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/state/MutationFeedback.kt`: `MutationFeedback`
