import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
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
        versionCode = 1_500_000_701
        versionName = "0.7.1"
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
        buildConfigField("boolean", "BENCHMARK_FIXTURES_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("releaseAcceptance") {
            initWith(getByName("release"))
            applicationIdSuffix = ".acceptance"
            versionNameSuffix = "-acceptance"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        // Created and finalized by the Baseline Profile plugin. It retains release R8/resource
        // behavior while using the debug key for connected profile collection and measurement.
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += "release"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            buildConfigField("boolean", "BENCHMARK_FIXTURES_ENABLED", "true")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
    }
}

dependencies {
    implementation(project(":app-logic"))
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
    implementation(libs.androidx.profileinstaller)
    implementation(libs.gson)

    baselineProfile(project(":baseline-profile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(project(":core-stubs"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

val verifyBenchmarkFixtureArtifact = tasks.register<Exec>("verifyBenchmarkFixtureArtifact") {
    group = "verification"
    description = "Verifies the packaged benchmark-only activity, process, dex, and media fixture."
    dependsOn("packageBenchmarkRelease")
    val benchmarkApk = layout.buildDirectory.file(
        "outputs/apk/benchmarkRelease/app-benchmarkRelease.apk",
    )
    val fixtureVideo = layout.projectDirectory.file(
        "src/benchmarkRelease/res/raw/benchmark_loop.mp4",
    )
    val verifierScript = rootProject.layout.projectDirectory.file(
        "scripts/verify_benchmark_fixture_apk.py",
    )
    val analyzer = androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve(
        "cmdline-tools/latest/bin/apkanalyzer",
    )
    inputs.file(benchmarkApk)
    inputs.file(fixtureVideo)
    inputs.file(verifierScript)
    commandLine(
        "python3",
        verifierScript.asFile,
        benchmarkApk.get().asFile,
        fixtureVideo.asFile,
        analyzer,
    )
}

tasks.matching { task -> task.name == "assembleBenchmarkRelease" }.configureEach {
    finalizedBy(verifyBenchmarkFixtureArtifact)
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

fun registerR8JsonContractVerification(variantName: String) {
    val capitalizedVariant = variantName.replaceFirstChar(Char::uppercaseChar)
    val verification = tasks.register<Exec>("verify${capitalizedVariant}JsonContracts") {
        group = "verification"
        description = "Verifies stable Gson field names in the $variantName R8 mapping."
        dependsOn("minify${capitalizedVariant}WithR8")
        val mappingFile = layout.buildDirectory.file("outputs/mapping/$variantName/mapping.txt")
        val contractManifest = project.file("src/test/resources/r8-json-contracts.txt")
        inputs.file(mappingFile)
        inputs.file(contractManifest)
        commandLine(
            "python3",
            rootProject.file("scripts/verify_r8_json_contract.py"),
            mappingFile.get().asFile,
            contractManifest,
        )
    }
    tasks.matching { task -> task.name == "assemble$capitalizedVariant" }.configureEach {
        finalizedBy(verification)
    }
}

registerR8JsonContractVerification("releaseAcceptance")
registerR8JsonContractVerification("release")
