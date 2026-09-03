# Working List

## Pending

- [!] Deploy and validate the batch gateway without compatibility endpoints
  - Production deployment was blocked because the user must explicitly authorize replacing the live `/translate` endpoint with batch-only `/translate-batch`; no live service changed.

## In Progress

- None.

## Done

- [x] Initialize the task checklist
  - Replaced the stale Search checklist before implementation, as required by the task-orchestrator workflow.
- [x] Define the integrated data flow, limits, error paths, and tests
  - OCR falls back from the original image to Japanese-only overlapping crops and one contrast treatment. Translation resolves durable cache hits, batches misses, shares in-flight work with taps, and rejects stale identities.
- [x] Add a bounded Japanese OCR recovery pass for stylized text
  - Verified four overlapping crops, overlap deduplication, Android bitmap preprocessing, and Debug compilation with focused app-logic tests.
- [x] Add persistent bounded translation cache storage and migration
  - Verified cache TTL/LRU behavior in core-data and Room, generated schema 8, and confirmed Room stores a 64-character source hash rather than plaintext source text.
- [x] Add background batch translation through the app and Google gateway
  - Verified deduplicated batch JSON, bounded splitting, durable cache reuse, one automatic current-image request, in-flight tap sharing, timed cards, and retry behavior in focused JVM/Python tests.
- [x] Run bounded validation and update the ExecPlan
  - Focused suites, Room migration, Android-test compilation, affected Detekt tasks, aggregate Kover verification, Debug assembly, hotspot/duplication audits, Compose normalization, HTML parsing, and diff integrity pass. Core-data Detekt retains three unrelated pre-existing findings.
