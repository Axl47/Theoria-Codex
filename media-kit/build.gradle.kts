import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

extensions.configure<LibraryExtension> {
    namespace = "com.theoriacodex.mediakit"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Moved debug-only code reads BuildConfig.DEBUG, which follows the variant :app selects.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":app-logic"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-data-android"))
    implementation(project(":core-sources"))
    implementation(project(":ui-kit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.vectordrawable.animated)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.android.animation.webp) {
        exclude(group = "androidx.appcompat", module = "appcompat")
    }
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(project(":core-stubs"))
    testImplementation(testFixtures(project(":core-data")))
    testImplementation(testFixtures(project(":ui-kit")))
}

// PixivUgoiraArchiveTest reads this :feed-kit source file, which :media-kit does not depend on, so it has
// to be a declared input for an edit to it to rerun the test.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchResultCard.kt"))
        .withPropertyName("searchResultCardSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
