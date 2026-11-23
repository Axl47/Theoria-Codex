# Extensions are compiled against Mihon's or Aniyomi's API and call the app's classes by name once loaded, so the
# shrinker must leave those names and members alone. It cannot see extension code, so nothing here is inferred: the
# list is the packages that samples of 300 manga and 168 anime extensions referenced (README, "Release builds").
# Whole packages, because rules for exactly the sampled members left out what 3 in 10 other extensions call, and rules
# per sampled class 1 in 15. extension-symbols.txt holds what the samples referenced; scripts/check.sh fails when a
# release build stops keeping one. QuickJS, the JavaScript engine, is the second native library here beside zstd.

# Mihon's and Aniyomi's API, the service locator, and the settings classes extensions fill: this module's own code.
-keep class eu.kanade.tachiyomi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class androidx.preference.** { *; }

# HTTP: OkHttp and Okio, and the decoders every extension's shared client names.
-keep class okhttp3.* { public protected *; }
-keep class okhttp3.brotli.* { public protected *; }
-keep class okhttp3.zstd.* { public protected *; }
# The zstd decoder's native library finds these classes and their private members by name, which no dex reference
# shows, so the symbol check cannot see them. Without them a zstd-compressed answer aborts the whole process.
-keep class com.squareup.zstd.** { *; }
-keep class okio.* { public protected *; }
# Two internals, each used by one extension of the sample.
-keep class okhttp3.internal._UtilCommonKt { public static void closeQuietly(java.io.Closeable); }
-keep class okhttp3.internal.connection.RealCall { public okhttp3.OkHttpClient getClient(); }

# JavaScript, which extensions evaluate with QuickJS. Its native library finds the engine's classes and members by name
# and calls into the interfaces scripts are bound to, which no dex reference shows, so the classes are kept whole.
-keep class app.cash.quickjs.** { *; }

# HTML parsing.
-keep class org.jsoup.* { public protected *; }
-keep class org.jsoup.helper.* { public protected *; }
-keep class org.jsoup.nodes.* { public protected *; }
-keep class org.jsoup.parser.* { public protected *; }
-keep class org.jsoup.select.* { public protected *; }

# JSON. Generated serializers in extensions implement and call `internal`.
-keep class kotlinx.serialization.* { public protected *; }
-keep class kotlinx.serialization.builtins.* { public protected *; }
-keep class kotlinx.serialization.descriptors.* { public protected *; }
-keep class kotlinx.serialization.encoding.* { public protected *; }
-keep class kotlinx.serialization.internal.* { public protected *; }
-keep class kotlinx.serialization.json.* { public protected *; }
-keep class kotlinx.serialization.json.okio.* { public protected *; }
-keep class kotlinx.serialization.modules.* { public protected *; }

# Coroutines.
-keep class kotlinx.coroutines.* { public protected *; }
-keep class kotlinx.coroutines.sync.* { public protected *; }

# The Kotlin standard library, which extensions do not carry.
-keep class kotlin.* { public protected *; }
-keep class kotlin.collections.* { public protected *; }
-keep class kotlin.comparisons.* { public protected *; }
-keep class kotlin.coroutines.* { public protected *; }
-keep class kotlin.coroutines.intrinsics.* { public protected *; }
-keep class kotlin.coroutines.jvm.internal.* { public protected *; }
-keep class kotlin.enums.* { public protected *; }
-keep class kotlin.io.* { public protected *; }
-keep class kotlin.jdk7.* { public protected *; }
-keep class kotlin.jvm.functions.* { public protected *; }
-keep class kotlin.jvm.internal.* { public protected *; }
-keep class kotlin.jvm.internal.markers.* { public protected *; }
-keep class kotlin.properties.* { public protected *; }
-keep class kotlin.random.* { public protected *; }
-keep class kotlin.ranges.* { public protected *; }
-keep class kotlin.reflect.* { public protected *; }
-keep class kotlin.sequences.* { public protected *; }
-keep class kotlin.text.* { public protected *; }
-keep class kotlin.time.* { public protected *; }
-keep class kotlin.time.jdk8.* { public protected *; }
