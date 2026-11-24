import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

extensions.configure<LibraryExtension> {
    namespace = "com.theoriacodex.feedkit"
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
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Shared test helpers (search coordinator doubles, mirror posts, tag chips, Viewer return feed) that
    // tests in :app and the feature modules reuse.
    testFixtures {
        enable = true
    }
}

dependencies {
    implementation(project(":app-logic"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-sources"))
    implementation(project(":ui-kit"))
    implementation(project(":media-kit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(project(":app-logic"))
    testFixturesImplementation(project(":core-domain"))
    testFixturesImplementation(project(":core-data"))
    testFixturesImplementation(project(":media-kit"))
    testFixturesImplementation(testFixtures(project(":core-data")))
    testFixturesImplementation(testFixtures(project(":ui-kit")))
    testFixturesImplementation(libs.androidx.compose.ui.test.junit4)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    // The Compose compiler plugin also runs on test fixtures and refuses a classpath without the runtime.
    testFixturesImplementation(platform(libs.androidx.compose.bom))
    testFixturesImplementation(libs.androidx.compose.ui)

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
