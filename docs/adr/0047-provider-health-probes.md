# 0047. Provider health probes report CANNOT_CHECK behind a challenge and keep public output free of provider data

Status: Accepted

## Context

Health probes run against live providers, including unattended runs whose logs are public. A browser challenge says nothing about whether the provider is up, and provider-derived output in public logs is a data and safety risk.

## Decision

- `ChallengeObservingHttpClient` counts browser challenges answered to a probe; a step that fails while a challenge was seen is reported as `ProviderHealthStatus.CANNOT_CHECK`, not as down. A maintenance page is an outage and does not count as a challenge.
- `HitomiProviderHealthProbe` checks provider-owned routes a generic search probe cannot prove. Only its two media range steps download media bytes; `probeMediaRanges = false` skips them for unattended public runs.
- `publicSafeSummaryLines` prints one `<source id>: <status> <reason>` line per source and nothing provider-derived (no URLs, messages, ids, counts or latencies).

## Consequences

A challenged provider shows as unknown rather than failing the run. New health output must stay within the public-safe line format when run publicly.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/health/ChallengeObservingHttpClient.kt`: `ChallengeObservingHttpClient`, `isBrowserChallenge`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/health/HitomiProviderHealthProbe.kt`: `HitomiProviderHealthProbe`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/health/ProbeSteps.kt`: `observingChallenges`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/health/ProviderHealth.kt`: `CANNOT_CHECK`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/health/ProviderHealthCheckCli.kt`: `publicSafeSummaryLines`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/health/ChallengeObservingHttpClientTest.kt`: `maintenance pages and plain refusals are not browser challenges (ADR 0047)`, `counts only the challenges it passes through on every transport (ADR 0047)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/health/HitomiProviderHealthProbeTest.kt`: `disabling media ranges skips both byte probes and keeps configuration and gallery steps (ADR 0047)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/health/ProviderHealthCheckCliTest.kt`: `public safe summary lists only source status and reason enum (ADR 0047)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/health/ProviderHealthCheckerTest.kt`: `a problem during a browser challenge cannot be checked while a real failure stays failed (ADR 0047)`
- Left to prose: public runs passing `probeMediaRanges = false` and printing only the safe summary are wiring inside the CLI's `main`, which runs live providers, so a test would need a seam the CLI does not have.
