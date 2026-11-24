import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

extensions.configure<LibraryExtension> {
    namespace = "com.theoriacodex.extensionhost"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // What the app's shrinker must keep for extensions, which link against this module's dependencies by name.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Only this module's test APK is packaged here. Its native libraries are extracted so the process map names them,
    // which is how the anime harness sees that an extension started QuickJS.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core-domain"))
    // The browser cookie store and cookie jar the app's own clients share.
    implementation(project(":core-sources"))
    // Extension binaries link against these at run time, so they are part of this module's public surface.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Most extensions parse pages with Jsoup; Aniyomi's ParsedAnimeHttpSource and asJsoup name it in their signatures.
    api(libs.jsoup)
    // Not called from this module: every extension's HTTP client names the first two, and most extensions decode
    // JSON straight from the response stream.
    runtimeOnly(libs.okhttp.brotli)
    runtimeOnly(libs.okhttp.zstd)
    runtimeOnly(libs.kotlinx.serialization.json.okio)
    // Newer extension repos publish their index as protobuf (Mihon's `index_v2`).
    implementation(libs.kotlinx.serialization.protobuf)
    // The JavaScript engine extensions evaluate scripts with (a third of anime extensions, some manga ones).
    runtimeOnly(libs.quickjs.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.quickjs.android)
}
