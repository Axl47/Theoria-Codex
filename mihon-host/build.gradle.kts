import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

extensions.configure<LibraryExtension> {
    namespace = "com.theoriacodex.mihonhost"
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
}

dependencies {
    implementation(project(":core-domain"))
    // Extension binaries link against these at run time, so they are part of this module's public surface.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // Not called from this module: every extension's HTTP client names the first two, and most extensions decode
    // JSON straight from the response stream and parse pages with Jsoup.
    runtimeOnly(libs.okhttp.brotli)
    runtimeOnly(libs.okhttp.zstd)
    runtimeOnly(libs.kotlinx.serialization.json.okio)
    runtimeOnly(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
