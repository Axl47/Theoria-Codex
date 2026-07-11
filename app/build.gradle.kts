import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.theoriacodex.app"
    compileSdk = 37

    val versionCodeOverride = providers.gradleProperty("theoria.versionCode")
        .orNull
        ?.toIntOrNull()
    val versionNameOverride = providers.gradleProperty("theoria.versionName").orNull

    defaultConfig {
        applicationId = "com.theoriacodex"
        minSdk = 26
        targetSdk = 37
        // Keep this calculation aligned with MainReleaseTagParser and the release workflow:
        // 1_500_000_000 + major * 10_000 + minor * 100 + patch.
        versionCode = 1_500_000_603
        versionName = "0.6.3"
        if (versionCodeOverride != null) {
            versionCode = versionCodeOverride
        }
        if (!versionNameOverride.isNullOrBlank()) {
            versionName = versionNameOverride
        }

        buildConfigField("String", "UPDATE_REPO_OWNER", "\"Axl47\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"Theoria-Codex\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"main\"")
        buildConfigField("String", "UPDATE_ASSET_NAME", "\"theoria-codex-main.apk\"")
        buildConfigField("long", "UPDATE_CHECK_TIMEOUT_MS", "3000L")
        buildConfigField("boolean", "UPDATER_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-data-android"))
    implementation(project(":core-sources"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.vectordrawable.animated)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.android.animation.webp) {
        exclude(group = "androidx.appcompat", module = "appcompat")
    }
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-stubs"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "theoria.liveSources",
        providers.gradleProperty("theoria.liveSources").orElse("false").get(),
    )
    providers.gradleProperty("theoria.liveSources.sources").orNull?.let { sources ->
        systemProperty("theoria.liveSources.sources", sources)
    }
    providers.gradleProperty("theoria.providerProbeCases").orNull?.let { caseFile ->
        systemProperty("theoria.providerProbeCases", caseFile)
    }
}
