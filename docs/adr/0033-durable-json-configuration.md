# 0033. All saved data uses one kotlinx Json configuration that keeps Gson-era behaviour

Status: Accepted

## Context

Saved data was written by Gson before kotlinx.serialization replaced it. Older builds, backups and hand-edited files must keep loading, and older builds must read what the current one writes.

## Decision

- `DurableJson` is the shared configuration for files, Room payloads, exports, and fixed-shape network, asset and developer-copy JSON; there are two exceptions. Series backup memos are checked as JSON objects with plain `Json` (`storedMemo`), because lenient `DurableJson` would accept text that is not JSON as a memo and the strict check is the behaviour kept. `DeviceExtensionChoices` reads and writes `extension_choices.json` with kotlinx's defaults and `ignoreUnknownKeys`, and moving it onto `DurableJson` would change the file it writes and what it accepts. Each flag preserves Gson behaviour:
  - `ignoreUnknownKeys`: newer files and retired fields are not rejected.
  - `encodeDefaults`: backup validation requires defaulted keys to be present, as Gson wrote them.
  - `explicitNulls = false`: Gson omits nulls, and some readers treat a present key as data.
  - `coerceInputValues`: a stored null on a defaulted field repairs to the default instead of resetting the store.
  - `isLenient`: the reason first given, that Gson accepted quoted primitives such as "5" for an Int, was wrong, because kotlinx accepts quoted primitives without it. The flag stays only because removing it could reject stored files not yet seen; issue #486 tracks deciding whether any written or shipped file relies on it.
- `decodeEachOrSkip` drops list elements whose decoding throws `SerializationException`, so one malformed entry never hides well-formed neighbours; any other exception propagates.
- `storedVersion` reads a top-level integer version without decoding the rest, so the DataStore-backed stores refuse a newer build's file whatever its shape.

## Consequences

Changing a flag changes how every stored file loads; the golden-file tests pin the effect.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/storage/DurableJson.kt`: `DurableJson`, `decodeEachOrSkip`, `storedVersion`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreGoldenTest.kt`: `settings null on a defaulted boolean repairs to the default (ADR 0033, 0034)`, `settings unknown and retired keys are ignored (ADR 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/BackupGoldenTest.kt`: `a backup written now is the document the Gson codec wrote (ADR 0024, 0033)`
- `detekt-rules/src/test/kotlin/com/theoriacodex/detekt/StoredJsonUsesDurableJsonRuleTest.kt`: `building or calling kotlinx's Json in an expression is reported (ADR 0033)`, `Json as a type, inside a string, or a different Json is not reported (ADR 0033)`, `the Theoria rule set runs the stored JSON rule (ADR 0033)`, `a suppression on one declaration excuses only that declaration (ADR 0033)`
- `config/detekt/detekt.yml`: `StoredJsonUsesDurableJsonRule: active: true excludes: ['**/DurableJson.kt', '**/core-sources/src/**', '**/extension-host/src/**', '**/app/src/releaseAcceptance/**']`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ReadingPositionRepositoryTest.kt`: `malformed entries do not discard valid positions (ADR 0021, 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/storage/DurableJsonTest.kt`: `a record check that fails while decoding a list element is not skipped (ADR 0033)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `newer settings schema fails closed without corruption replacement (ADR 0032, 0033)`
- Left to prose: `isLenient` has no guard, because what it would protect is stored files not yet seen, which no test can hold, and issue #486 decides whether it stays; the Detekt rule `StoredJsonUsesDurableJsonRule` reports `Json(DurableJson) { }` as well, because a derived configuration can override any flag, so a format that needs other flags is a new decision in this record; `storedMemo` and `DeviceExtensionChoices`' `json` are each excused by a suppression on that one declaration. The rule sees what Detekt analyses, main sources and Android modules' debug sources, so test, test-fixture and instrumented-test code may build its own `Json` to inspect output; `app/src/releaseAcceptance` is excluded for the same reason, since its release acceptance checks parse written files into JSON trees to compare them and store nothing; it resolves `Json` through imports alone, so it misses a typealias of `Json`, a `Json` instance handed in from code it does not see, and Java sources; it covers every module except `:core-sources` and `:extension-host`, which parse provider and extension-repository formats with their own `Json` instances, so whether those instances follow this record is unguarded.
