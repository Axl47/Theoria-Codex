# 0033. All saved data uses one kotlinx Json configuration that keeps Gson-era behaviour

Status: Accepted

## Context

Saved data was written by Gson before kotlinx.serialization replaced it. Older builds, backups and hand-edited files must keep loading, and older builds must read what the current one writes.

## Decision

- `DurableJson` is the shared configuration for files, Room payloads, exports, and fixed-shape network, asset and developer-copy JSON; the exception is series backup memos, which are checked as JSON objects with plain `Json` (`storedMemo`). Each flag preserves Gson behaviour:
  - `ignoreUnknownKeys`: newer files and retired fields are not rejected.
  - `encodeDefaults`: backup validation requires defaulted keys to be present, as Gson wrote them.
  - `explicitNulls = false`: Gson omits nulls, and some readers treat a present key as data.
  - `coerceInputValues`: a stored null on a defaulted field repairs to the default instead of resetting the store.
  - `isLenient`: Gson accepted quoted primitives such as "5" for an Int and "true" for a Boolean.
- `decodeEachOrSkip` drops list elements whose decoding throws `SerializationException`, so one malformed entry never hides well-formed neighbours; any other exception propagates.
- `storedVersion` reads a top-level integer version without decoding the rest, so the DataStore-backed stores refuse a newer build's file whatever its shape.

## Consequences

Changing a flag changes how every stored file loads; the golden-file tests pin the effect.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DurableJson.kt`: `DurableJson`, `decodeEachOrSkip`, `storedVersion`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreGoldenTest.kt`: `settings null on a defaulted boolean repairs to the default (ADR 0033, 0034)`, `settings unknown and retired keys are ignored (ADR 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `a backup written now is the document the Gson codec wrote (ADR 0024, 0033)`
- `scripts/tests/test_check_architecture.py`: `test_reports_stored_json_configured_outside_durable_json_adr_0033`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionRepositoryTest.kt`: `malformed entries do not discard valid positions (ADR 0021, 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/DurableJsonTest.kt`: `a record check that fails while decoding a list element is not skipped (ADR 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `newer settings schema fails closed without corruption replacement (ADR 0032, 0033)`
- Left to prose: `isLenient` has no guard, because kotlinx reads quoted numbers and booleans without it and no stored file depends on what it adds; the policy rule misses a `Json` imported under another name and a `Json(base) { }` built from a base other than `DurableJson`; it covers every module except `:app`, whose `extension_choices.json` exception is the app batch's call, and `:core-sources` and `:extension-host`, whose own `Json` instances for provider and repository formats are left to their batches.
