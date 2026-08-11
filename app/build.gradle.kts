import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class VerifyR8JsonContractsTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val seedsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verifierScript: RegularFileProperty

    @TaskAction
    fun verify() {
        execOperations.exec {
            commandLine(
                "python3",
                verifierScript.get().asFile,
                mappingFile.get().asFile,
                contractManifest.get().asFile,
                seedsFile.get().asFile,
            )
        }
    }
}

abstract class VerifyInstallableApplicationIdTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val outputMetadata: RegularFileProperty

    @get:Input
    abstract val expectedApplicationId: Property<String>

    @TaskAction
    fun verify() {
        val metadata = outputMetadata.get().asFile.readText()
        val packagedApplicationIds = Regex("\"applicationId\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(metadata)
            .map { match -> match.groupValues[1] }
            .toSet()
        check(packagedApplicationIds == setOf(expectedApplicationId.get())) {
            "Expected packaged application ID ${expectedApplicationId.get()}, " +
                "but ${outputMetadata.get().asFile} declared $packagedApplicationIds"
        }
    }
}

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
        versionCode = 1_500_001_000
        versionName = "0.10.0"
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
            applicationIdSuffix = ".baselineprofile"
            versionNameSuffix = "-baselineprofile"
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
    val deviceSafeApplicationIds = mapOf(
        "debug" to "com.theoriacodex.debug",
        "releaseAcceptance" to "com.theoriacodex.acceptance",
        "benchmarkRelease" to "com.theoriacodex.benchmark",
        "nonMinifiedRelease" to "com.theoriacodex.baselineprofile",
    )
    onVariants(selector().all()) { variant ->
        val expectedApplicationId = deviceSafeApplicationIds[variant.name] ?: return@onVariants
        val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verification = tasks.register<VerifyInstallableApplicationIdTask>(
            "verify${capitalizedVariant}InstallableApplicationId",
        ) {
            group = "verification"
            description = "Fails before device work if ${variant.name} can replace production."
            dependsOn("package$capitalizedVariant")
            outputMetadata.set(
                layout.buildDirectory.file("outputs/apk/${variant.name}/output-metadata.json"),
            )
            this.expectedApplicationId.set(expectedApplicationId)
        }
        tasks.matching { task ->
            task.name == "install$capitalizedVariant" ||
                task.name == "connected${capitalizedVariant}AndroidTest"
        }.configureEach {
            dependsOn(verification)
        }
    }

    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
    }

    setOf("release", "releaseAcceptance").forEach { buildType ->
        onVariants(selector().withBuildType(buildType)) { variant ->
            val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
            val verification = tasks.register<VerifyR8JsonContractsTask>(
                "verify${capitalizedVariant}JsonContracts",
            ) {
                group = "verification"
                description = "Verifies stable Gson field names in the ${variant.name} R8 mapping."
                mappingFile.set(
                    variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE),
                )
                seedsFile.set(
                    layout.buildDirectory.file("outputs/mapping/${variant.name}/seeds.txt"),
                )
                contractManifest.set(
                    layout.projectDirectory.file("src/test/resources/r8-json-contracts.txt"),
                )
                verifierScript.set(
                    rootProject.layout.projectDirectory.file(
                        "scripts/verify_r8_json_contract.py",
                    ),
                )
            }
            tasks.matching { task -> task.name == "assemble$capitalizedVariant" }.configureEach {
                finalizedBy(verification)
            }
        }
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
    implementation(libs.androidx.lifecycle.process)
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
