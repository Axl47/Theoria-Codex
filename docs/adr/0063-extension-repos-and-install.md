# 0063. Extensions install only from a repo's named key, into a private folder, and load only from verified read-only copies

Status: Accepted

## Context

Extension APKs run with the app's permissions and can read its data. Repo indexes and APKs come from the network and may be hostile (huge, compressed bombs, mismatched, signed by another key). Android will not load code from a file the app could still write.

## Decision

- A repo is stored with the signing key it named when added; an APK installs only if it is the package the index named and signed by that key. A repo that later names another key is skipped until re-added. Index entries are untrusted: `installFrom` checks the name is a valid package name, the repo and entry still exist, any installed copy is signed with the repo's key, then the downloaded APK's package name equals the requested one, its signer equals the repo's key, its version is not older than the installed copy, and the host supports it. The APK's version and kind are not compared with the index entry. Repo urls must be https. A key names a publisher, not a repo, so repos may share name and key; a package two repos offer is listed from the first. Torrent entries and unsupported `libVersion`s are filtered out.
- Downloads go to `<package>.apk.new` (not listed until checked), validated against `PACKAGE_NAME` (it names the file), then moved with `ATOMIC_MOVE` inside `NonCancellable` along with the trust and registry update. Code already loaded runs on from its own locked copy. A newly installed extension is turned on (the user chose it from a repo they added); an update keeps its choice. `onChanged` runs after each change because Android sends no broadcast for the folder. Only `ExtensionCatalog` writes the folder.
- Indexes and APKs are size limited (`MAX_INDEX_BYTES` 8 MiB, applied to fetched and gunzipped bodies since a small body can expand enormously; Keiyoushi's is well under 1 MiB; `MAX_APK_BYTES`).
- `RepoReader` accepts the repo folder, either JSON file, a legacy `repo.json` naming a newer index (`index_v2`; Keiyoushi's `index.min.json` is a stub), or a protobuf index (usually gzipped). Anime and manga protobuf layouts differ (field 8) and a store states neither; the anime layout is tried first, which a manga store fails, and a store listing no sources fits anime but names Mihon's dotted interface versions.
- Installed packages load from Android's own read-only copy. Private copies and debug files are copied and locked first (Android refuses writable code), each load gets its own never-rewritten copy, and the copy must match the listed file's package, signer, version and length. Leftover copies are removed once per process; the folder keeps the name `mihon-extensions`.
- `ExtensionPackage` equality includes version code, size and modified time so code loaded for one value is never taken for another. `identity` is the package name (an update is the same extension) except debug files, which are keyed by file name plus package. `id` for a file adds the package so a question about one extension cannot be answered for another put in its place. Only a single signer is accepted. A private copy stands in for an installed package of the same name; only a newer package with the same key replaces it, so another app cannot swap in a copy signed with another key. Seeing other apps' packages needs `QUERY_ALL_PACKAGES`.
- An index body over `MAX_INDEX_BYTES` (8 MiB) is refused, by declared length and by reading at most that many bytes, including after gzip expansion.
- A repo is kept with the key its `repo.json` names; when a backup knew a key and the repo now names another, nothing it offers is listed until the user adds it again.
- Adding the same repo twice, or a second address of it, lists its packages once. Another repo with the same signing key and name is kept.
- Each index entry's source ids (legacy `sources[].id`, protobuf `Source` field 1) become `RepoExtension.sources`, as source keys of the entry's kind. A followed series whose source isn't loaded is traced to the extension that provides it (`missingExtensions`). Where several repos list the source, the copy the device runs wins, then an update, then an installable copy, then one signed with another key. Series rows keep only their source key, so a source no repo lists is shown by series title, grouped under the registrable domain of the series' cover URL (`siteHint`), decided from the URL alone.
- Repo index fixtures are protobuf: built from `repos/store.txt` and `repos/anime.txt` with `protoc --encode=Store store.proto < store.txt | gzip -9` (`--encode=AnimeStore` for anime).

## Consequences

Adding a source of APKs means passing the same key and package checks. Revisit the key model only with a new trust design.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCatalog.kt`: `ExtensionCatalog`, `addOne`, `installFrom`, `MAX_INDEX_BYTES`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/RepoIndex.kt`: `RepoReader`, `IndexedExtension`, `RepoReader.gunzipped`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionLoader.kt`: `ExtensionLoader`, `privateReadOnlyCopy`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionPackages.kt`: `ExtensionPackage`, `ExtensionPackages`, `standing`, `signer`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCatalog.kt`: `MAX_INDEX_BYTES`, `ExtensionCatalog`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/RepoIndex.kt`: index reading
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/MissingExtensions.kt`: `missingExtensions`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionCatalogTest.kt`: `a repo is added with the key it names, once per address, and refused when a backup knew another key (ADR 0007, 0063)`, `nothing is installed that is not the package the repo named, signed with its key, and no older than the copy on the device (ADR 0007, 0063)`, `each extension the host runs is listed with how the device stands with it (ADR 0063)`, `torrent extensions and ones of an interface the host does not run are not offered (ADR 0063)`, `an extension installed from a repo is kept privately and turned on, an update keeps the user's choice, and uninstalling turns it off (ADR 0007, 0063)`, `a repo whose index is over 8 MiB, as declared, as sent or once gunzipped, is refused without reading it all (ADR 0063)`, `an extension download declared over its size limit is refused before any of it is read (ADR 0063)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionLoaderTest.kt`: `a private or debug copy loads only while its package, signer, version and size match the listing, locked, and any other copy is deleted before code loads (ADR 0063)`, `an installed package loads from Android's own copy, and no copy is made (ADR 0063)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionPackagesTest.kt`: `a private copy stands in for a package of its name, unless the other app updated it past the copy (ADR 0063)`, `only a package signed by exactly one certificate has a signer (ADR 0063)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionRegistryTest.kt`: `another extension put under a folder file's name with the same key is not the one asked about or turned on (ADR 0062, 0063)`, `a file replaced in place loads anew and its old code is no longer offered (ADR 0062, 0063)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/RepoReaderTest.kt`: `a legacy repo is read from its folder, its index address or its repo address alike (ADR 0063)`, `a legacy repo naming a newer index is read from that index, as Keiyoushi's is (ADR 0063)`, `a legacy repo naming a newer index that cannot be read fails instead of reading the stub list (ADR 0063)`, `an anime store is read from its own address, with its torrent flag where a manga store has sources (ADR 0063)`, `a manga store whose extensions list no sources is still read as manga (ADR 0063)`
- `feature-series/src/test/kotlin/com/theoriacodex/app/series/MissingExtensionsTest.kt`: `each missing series goes to the extension that provides its source, or to the unlisted group (ADR 0063)`
- Left to prose: only `ExtensionCatalog` writing the folder has no precise check (the folder travels as a `File`, so a text rule would both miss and misfire); `ATOMIC_MOVE` inside `NonCancellable` has no guard, since the move's atomicity is the file system's; leftover copies being removed once per process and `MAX_APK_BYTES` stopping a download that declares no length are not exercised; `QUERY_ALL_PACKAGES` is a manifest permission; the key model is the trust design.
