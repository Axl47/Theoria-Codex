# 0158. Source credentials are encrypted in one envelope, never leak into errors or logs, and are cleared only on the user's request

Status: Accepted

## Context

Pixiv tokens and booru API keys (including keys of user-added sites) are stored in an Android Keystore-encrypted envelope. Parse errors from the JSON library can quote their input, which here can be a decrypted payload, and a store that cannot be read must never be wiped automatically.

## Decision

- The envelope file location is resolved lazily (`noBackupFilesDir` is used for ordinary operation), so constructing the application graph performs no storage I/O. A developer export (`allowAtomicRecovery` false) neither repairs atomic-write artifacts (.bak/.new) nor creates the no-backup directory while reading and fails on an unfinished write; an ordinary read may recover only the committed .bak file.
- Redaction: `SourceCredentialSnapshot`, `CredentialPayloadRecord` and `CustomSiteCredentialRecord` redact `toString`; the snapshot's equality verifies committed envelope writes. A parse or decode failure is replaced by `CredentialEnvelopeCorruptException` with no message or cause, because kotlinx quotes part of its input in errors. A corrupt envelope or payload therefore asks for reconnection.
- `toCredentialReadFailure`: a lost key (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `InvalidKeyException`), a bad authentication tag or a corrupt envelope asks to reconnect; any other failure, such as IO or the keystore, may pass.
- Versions: `newerCredentialVersion` reads the first stored version newer than this build supports before the typed decode, so a newer build's format reports `UnsupportedVersion` whatever its shape; a later version only counts once every earlier one matches.
- Old Gson-written envelopes decode unchanged. The `customSites` map is additive in payload schema 1, written only when non-empty, and a decoded entry that is not a valid custom id, or lacks a non-blank user or key, is dropped without failing the read. A wrong-typed entry fails the typed decode instead and asks for reconnection.
- Recovery: an unreadable store is cleared only after the user chose to reconnect (`resetAfterReconnectRequired`); ordinary startup and reads never delete credential material. A reset may partially succeed or be cancelled, so the cached snapshot is dropped before it runs; both halves (envelope, key) are always attempted and each is idempotent on retry. A failed write invalidates the cache so a later read proves the authoritative store again.
- Recovery states split storage failures by the action available and carry no exception text, because provider credentials can occur in nested causes. `RecoverableSourceCredentialsStore` exposes recovery to the app shell without depending on the Android implementation; `SourceAccountStore` updates `availableSources` on credential changes instead of rebuilding the source registry and every coordinator that depends on it.
- Site keys are stored in the encrypted payload's `customSites` map, never in the site profile or a backup. Reads return null while the store is unavailable; writes then throw. `changes` emits on collection and after every committed change so the custom-source runtime can rebuild. `writeVerified` writes atomically and verifies a decrypting read-back before reporting success.
- A pasted credentials snippet (a full URL or bare `a=b&c=d`) is accepted only when both the user (`user_id` or `login`) and `api_key` are present; anything else is a plain typed value.

## Consequences

A store that cannot be decrypted blocks accounts until the user reconnects, but never loses credentials silently. Adding a credential field means extending the payload additively and keeping `toString` redacted.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/CredentialEnvelopeStore.kt`: `CredentialEnvelopeStore`, `CredentialPayloadRecord`, `toCredentialReadFailure`, `parseCredential`, `newerCredentialVersion`, `reset`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/VersionedSourceCredentialsStore.kt`: `CredentialStoreRecoveryState`, `SourceCredentialSnapshot`, `writeVerified`, `resetAfterReconnectRequired`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/SourceCredentialsStores.kt`: `SiteCredentialsStore`, `resetAfterReconnectRequired`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/ObservableSourceAccountStore.kt`: `RecoverableSourceCredentialsStore`, `SourceAccountStore`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/sourceauth/CredentialSnippetParser.kt`: `parseCredentialSnippet`
