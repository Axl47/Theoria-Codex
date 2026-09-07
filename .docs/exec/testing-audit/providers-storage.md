# Providers and storage remediation

Owner: providers_storage. Scope: core-domain, core-sources, core-data-android production/tests only.

- [x] F05: shared canonical post-page uniqueness boundary; real adapter response contracts retain raw continuation.
- [x] F05/F06: request-keyed AIBooru search/paging/suggestion/resolve and failure journey; representative KVS paging/resolve failure.
- [x] F06: weighted ordering outcomes with unequal queues.
- [x] F12: multiple OR groups, filtered continuation, dedup/order, cancellation/failure scenarios.
- [x] F08: on-disk older Room schema opened with production factory, meaningful persisted memberships, reopen and later writes.
- [x] Source consistency and diff review. Root owns combined Gradle validation; no device/network commands here.

Acceptance: deterministic tests execute production owners and reject wrong returned order, missing request terms, duplicate canonical IDs, false exhaustion, swallowed cancellation, and lost migrated memberships. No new test quota or source-spelling checks.

## Implemented evidence

- `RealProviderPageContractTest`: all nine non-Hitomi source adapters plus Pixiv/Gelbooru/Iwara creator pages publish unique canonical IDs while retaining source continuation. Existing Hitomi tests already cover raw-ID duplication and post-hydration aliases.
- `AibooruProviderJourneyTest`: exact TOP/exclusion/score query, full duplicate/malformed raw page -> next page -> failed resolve -> retry; distinct trending/autocomplete requests; parse/network/rate-limit/cancellation outcomes.
- `Rule34GenProviderJourneyTest`: request transcript for duplicate HTML cards, provider continuation, terminal page, 503 detail retry, selected media and 404.
- `UnifiedSearchOrchestratorTest`: complete weighted ordering including source exhaustion. `GroupedSearchPagingTest`: multiple OR groups, zero-visible continuation, exhausted branch isolation, canonical dedup and sort, partial-source failure isolation, actual cancellation cleanup.
- `RoomUpgradeScenario`: frozen v1 post/query payloads in on-disk schema 1/2/6, production factory migration to schema 9, profile Likes/shared collections/history/rules, two reopens and subsequent membership/cache writes. Shared by host `RoomUpgradeJourneyTest` and device `RoomUpgradeJourneyDeviceTest`.
- `canonicalPostPage` repairs demonstrated duplicate identities at the provider publication boundary without deriving continuation from visible count.

Validation: `git diff --check` passed; schema column order and production parser/request shapes reviewed. Root owns compiled host/device evidence. Required source-set wiring: `src/sharedTest/kotlin` in both test and androidTest (requested from root).

Conventional Commit suggestion: `fix(providers): preserve canonical identities and verify search and upgrade journeys`

## Delegated F03 tooling

Root additionally delegated `scripts/benchmark_results.py` and `scripts/tests/test_benchmark_results.py`.

- [x] `record` preserves the complete raw report directory, original report/Perfetto paths, artifact hashes, device/compilation/workload metadata, and per-iteration trace hashes.
- [x] `calibrate` requires at least three distinct complete physical runs on one target APK and the same harness/device/OS/compilation/workload. Canonical raw-report hashes reject a copied/reformatted report being counted again.
- [x] Default contract requires all seven benchmark methods and their full iteration counts. Required dimensions: startup median, CPU-frame/overrun P95, peak RSS/GPU, player churn, range-request count, and batch makespan. Transport bytes remain trace diagnostics, not a claimed JSON byte-volume gate.
- [x] `compare` accepts a changed target APK but rejects incompatible/missing/nonfinite/partial evidence and re-derives calibration from archived sources. Upper limit = maximum baseline full-run summary + observed baseline range, with no fixed percentage and no zero-baseline division.
- [x] Fourteen focused host tests passed, including individually injected startup/frame-tail/peak-memory/churn/transport regressions, recapture rejection, missing method/metric/iteration/trace, changed evidence, and metadata mismatch.
- [x] Existing real AndroidX report parsed using its explicit legacy five-method contract (21 metrics); the default seven-method contract correctly rejects it. This parser check is not fresh physical calibration evidence.

No runner script or device command was added. Root owns packaged identity/signing/physical run execution. Capture each completed output directory to a unique destination before the next connected run overwrites it.

F03 review corrections: capture schema v2 additionally archives all fixture source/resource files from `app/src/benchmarkRelease` and `app/src/fixtures` with relative-path SHA-256 manifest plus explicit contract digest. Workload identity is independent from the changeable target APK; identical content in another checkout remains comparable. Optional `--fixture-root CHECKOUT` supports explicit alternate source checkout and isolated tests. Both recording and loading reject recognizable raw emulator build context mislabeled physical, and require every method's `thermalThrottleSleepSeconds` to be explicitly zero. Positive throttle values require cooldown and a full rerun. Nineteen focused Python tests pass, including changed fixture code/media, added assets, archived-source tampering, emulator mislabeling, and thermal rejection. No device/Gradle commands.
