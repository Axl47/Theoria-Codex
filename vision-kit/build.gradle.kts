import com.android.build.api.dsl.LibraryExtension

// The opt-in picture tagger: model download, the LiteRT runner and what it predicts. Kept apart from media-kit so only
// the modules that tag pictures compile against the native runtime.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

extensions.configure<LibraryExtension> {
    namespace = "com.theoriacodex.visionkit"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":core-data"))
    // Aligns Coil's OkHttp with the version the rest of the app resolves.
    implementation(project(":core-sources"))
    implementation(project(":media-kit"))

    implementation(libs.litert)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
