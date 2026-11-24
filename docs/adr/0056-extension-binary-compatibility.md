# 0056. The host reproduces the Mihon and Aniyomi extension API under the names extension binaries expect

Status: Accepted

## Context

Extension APKs are compiled against Mihon's and Aniyomi's host libraries and call them by package, class and member name. They also find the shared library compiled into every extension, which rebuilds the host's HTTP client and refuses to run unless it finds three interceptors by simple class name. The sources here are derived from Mihon and Aniyomi (Apache License 2.0) and trimmed to what binaries call.

## Decision

- Package, class and member names under `eu.kanade.tachiyomi`, `eu.kanade.tachiyomi.animesource` and `androidx.preference` are fixed by extension binaries and are never renamed. Each derived file keeps its Mihon or Aniyomi attribution and the module keeps `LICENSE-APACHE-2.0.txt`.
- `androidx.preference` holds only holder classes. They draw nothing; the host reads what an extension put in. Members are limited to those found in use by surveys of 300 manga and 168 anime extensions. `OnBindEditTextListener` is held but never called because the app draws its own field.
- `UncaughtExceptionInterceptor`, `UserAgentInterceptor` and `CloudflareInterceptor` must keep those simple names; renaming or removing one breaks every extension.
- `MihonKind` accepts the `tachiyomi.extension` feature and extension API 1.6 (the only version the index held); `AniyomiKind` accepts `tachiyomi.animeextension` at interface 14 or 16 and refuses torrent extensions. Labels keep the prefixes extensions use ("Tachiyomi: ", "Aniyomi: ").
- Each `ExtensionKind` has its own source-id prefix (`sourceKey`) because both interfaces number sources the same way and two sources must never share an id. `AnimeHttpSource.id` is derived from the first eight bytes of MD5("name/lang/versionId") so it stays stable across installs; the exact byte order and masking are in 0065.
- `AnimeSource`, `Video` and `AnimeHttpSource` serve interfaces 14 and 16 with the suspend API only: nearly every extension is built against the server-list library, and none references RxJava, so the `rx.Observable` methods are absent and request/parse pairs are called directly. `Video`'s primary constructor is interface 16's layout, the secondary is 14's; Aniyomi's player-only arguments are ignored.
- A kind's `adapt` runs extension code within the load's time limit; anything it throws, or a neither-source-nor-factory instance, rejects the extension.
- Source, model and util classes under `eu.kanade.tachiyomi` and `uy.kohesive.injekt` keep their upstream package, class and member names; only what extension binaries call is kept. Files derived from Mihon or Aniyomi (Apache 2.0) keep that attribution and the licence text in the module.
- `Source` is the extension-lib 1.6 suspend API only; the deprecated RxJava methods are absent.
- `HttpSource` lets a source override the suspend functions or only the request/parse pairs, which the suspend functions drive directly.
- `Page.uri` is unused but stays in the constructor because extensions call it.
- `asJsoup` is provided here; the library's other helpers (`parseAs`, `parallelMap*`) are inline and compile into each extension.
- `Injekt` is a read-only stand-in registry bound only by the host runtime. `FullTypeReference` is subclassed anonymously by extensions to keep the type through erasure, and `TypeReference` keeps the name Aniyomi extensions call.
- Each file under `eu.kanade.tachiyomi` is trimmed to the members extension binaries call; code that only the original apps used is not carried over. `ChapterRecognition` is reduced further, to reading a chapter number from a chapter's name for chapters whose extension leaves the number out.
- The file-level `@Suppress("PropertyName", "VariableNaming", "FunctionName", "FunctionNaming")` on `SManga`, `SChapter`, `SAnime`, `SEpisode` and `Requests.kt` exists because binaries fix those member names.

## Consequences

Adding API surface means copying only what binaries call. A missing class or member surfaces as a `LinkageError` (see extension-isolation-and-failures). A new extension interface is a new `ExtensionKind`.

## Applies to

- `extension-host/src/main/kotlin/androidx/preference/Preferences.kt`: `Preference`, `EditTextPreference`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/HostInterceptors.kt`: `UncaughtExceptionInterceptor`, `UserAgentInterceptor`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt`: `CloudflareInterceptor`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/MihonKind.kt`, `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiKind.kt`, `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionKind.kt`: `MihonKind`, `AniyomiKind`, `ExtensionKind.sourceKey`, `ExtensionKind.adapt`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/animesource/`: `AnimeSource`, `AnimeHttpSource`, `ParsedAnimeHttpSource`, `Video`, `Hoster`, `SAnime`, `SEpisode`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/`: `AppInfo`, `HttpException`, `NetworkHelper`, `ConfigurableSource`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/Source.kt`: `Source`, `SourceFactory`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapter.kt`: `Page`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt`: `HttpSource`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/util/JsoupExtensions.kt`: `asJsoup`
- `extension-host/src/main/kotlin/uy/kohesive/injekt/Injekt.kt`: `Injekt`
- `extension-host/src/main/kotlin/uy/kohesive/injekt/api/InjektApi.kt`: `InjektScope`, `TypeReference`, `FullTypeReference`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ChapterRecognition.kt`: `ChapterRecognition`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/source/model/`: `SManga`, `SChapter`, `Filter`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/network/Requests.kt`, `OkHttpExtensions.kt`
- `extension-host/src/main/kotlin/eu/kanade/tachiyomi/util/JsoupExtensions.kt`

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSourceTest.kt`: `an anime source and a manga source with the same number are different sources (ADR 0056)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionImagesTest.kt`: `covers of two kinds' sources with the same numeric id are fetched by their own source (ADR 0056, 0059)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionLoaderTest.kt`: `an instance that is neither a source nor a factory, or whose constructor throws, rejects the extension (ADR 0056)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionPackagesTest.kt`: `a package is listed under the kind whose manifest names it declares, and one declaring none is no extension (ADR 0056)`, `a kind's reason for refusing an extension is the extension's (ADR 0056)`, `anime extensions of interface 14 and 16 are accepted, and any other version, an unreadable one or a torrent one is refused (ADR 0055, 0056)`
- `extension-host/src/test/kotlin/eu/kanade/tachiyomi/network/interceptor/HostInterceptorsTest.kt`: `the client extensions rebuild carries the interceptor names they require, in Mihon's order (ADR 0056, 0057)`
- `extension-host/src/test/kotlin/eu/kanade/tachiyomi/source/online/HttpSourceTest.kt`: `a source's id is the one Mihon and Aniyomi compute from its name, language and version (ADR 0056, 0065)`, `the extension API classes offer no RxJava method (ADR 0056)`
- `scripts/tests/test_check_architecture.py`: `test_reports_derived_code_without_its_attribution_or_licence_adr_0056`
- Left to prose: the package, class and member names binaries call (including `Video`'s two constructors) are checked by `extension_symbols_kept` in check.sh, which runs only in the release part after R8 and covers only what the sampled extensions referenced, so this batch could not mutation-prove it; the attribution rule reads only files under eu/kanade, so a derived file elsewhere (`ChapterRecognition`) keeps its notice by review; which members surveys found in use, holder classes that draw nothing, `OnBindEditTextListener` never being called, `Page.uri` staying in the constructor, the trimmed `ChapterRecognition` and the file-level Suppress annotations are judgments about what binaries call; the adapt step's time limit is guarded under 0060; `Injekt` being bound only by the host is guarded under 0062.
