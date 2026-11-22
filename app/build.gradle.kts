import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Lets links to private custom sites open in the app. Android only reads link filters from the manifest, so
 * this build's hosts are written into one here; they stay out of the source, but any APK still shows them.
 * Each site's family is found when it is set up, so the paths cover every family's post page.
 */
abstract class GenerateLinkHostsManifestTask : DefaultTask() {
    @get:Input
    abstract val hosts: ListProperty<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val hostData = hosts.get().joinToString("\n") { host -> "                <data android:host=\"$host\" />" }
        manifest.get().asFile.writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
                <application>
                    <activity android:name="com.theoriacodex.app.MainActivity">
                        <intent-filter tools:ignore="AppLinkWarning">
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="https" />
            $hostData
                            <data android:pathPrefix="/posts/" />
                            <data android:path="/index.php" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
    }
}

private val LINK_HOST = Regex("""([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]([a-z0-9-]*[a-z0-9])?""")

/**
 * The hosts in a comma- or line-separated list, each with its `www.` twin. A bad entry fails the build, and
 * its position is all the message names, because build logs can be public.
 */
fun parseLinkHosts(raw: String): List<String> =
    raw.split(',', '\n').map { it.trim().lowercase() }.filter(String::isNotEmpty).flatMapIndexed { index, host ->
        require(LINK_HOST.matches(host)) { "THEORIA_LINK_HOSTS entry ${index + 1} is not a plain host name" }
        listOf(host, if (host.startsWith("www.")) host.removePrefix("www.") else "www.$host")
    }.distinct().sorted()

abstract class GenerateMonochromeLauncherIconTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceIcon: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = checkNotNull(ImageIO.read(sourceIcon.get().asFile)) {
            "Cannot read launcher icon: ${sourceIcon.get().asFile}"
        }
        val monochrome = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val pixel = source.getRGB(x, y)
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                val gray = (299 * red + 587 * green + 114 * blue + 500) / 1000
                monochrome.setRGB(x, y, (pixel and -0x1000000) or (gray shl 16) or (gray shl 8) or gray)
            }
        }
        val output = outputDirectory.file("drawable-nodpi/theoria_app_icon.png").get().asFile
        output.parentFile.mkdirs()
        check(ImageIO.write(monochrome, "png", output)) { "Cannot write launcher icon: $output" }
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

abstract class VerifyPublicDeveloperCopyAbsentTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile.readText()
        check(listOf("DeveloperCopyProvider", "DeveloperCopyActivity", ".developer-copy").none(manifest::contains)) {
            "Public release contains a developer copy component"
        }
        check(!mappingFile.get().asFile.readText().contains("com.theoriacodex.app.developercopy.")) {
            "Public release compiled developer copy code"
        }
    }
}

abstract class RejectPersonalReleaseInstallTask : DefaultTask() {
    @TaskAction
    fun reject(): Unit = error("Personal production APKs must use the separately verified signed release/update path")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.theoriacodex.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    val versionCodeOverride = providers.gradleProperty("theoria.versionCode")
        .orNull
        ?.toIntOrNull()
    val versionNameOverride = providers.gradleProperty("theoria.versionName").orNull

    defaultConfig {
        applicationId = "com.theoriacodex"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        // Keep this calculation aligned with MainReleaseTagParser and the release workflow:
        // 1_500_000_000 + major * 10_000 + minor * 100 + patch.
        versionCode = 1_500_010_902
        versionName = "1.9.2"
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
        buildConfigField("boolean", "DEVELOPER_COPY_ENABLED", "false")
        buildConfigField("String", "DEVELOPER_COPY_CERT_SHA256", "\"\"")
        // Where the opt-in picture tagger's files download from; a test build may point it at a loopback server.
        val visionModelBaseUrl = providers.gradleProperty("theoria.visionModelBaseUrl")
            .orElse("https://github.com/Axl47/Theoria-Codex/releases/download/vision-models-1/").get()
        buildConfigField("String", "VISION_MODEL_BASE_URL", "\"$visionModelBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release builds carry the zstd decoder Mihon extensions expect of their host and LiteRT's runtime for the
        // picture tagger. Every device in the support window and the test emulators are 64-bit, so the 32-bit copies
        // are left out.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            buildConfigField("boolean", "DEVELOPER_COPY_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            // Saved data uses kotlinx.serialization, whose generated serializers need no app rules.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // Never add this source set to public release. Personal APKs are signed separately
        // with the existing production key; connected Gradle installation is forbidden.
        val copyCertificate = providers.gradleProperty("theoria.developerCopyCertSha256").orNull
        if (copyCertificate != null) {
            require(copyCertificate.matches(Regex("[0-9a-fA-F]{64}"))) {
                "theoria.developerCopyCertSha256 must be one SHA-256 certificate fingerprint"
            }
            create("personalRelease") {
                initWith(getByName("release"))
                matchingFallbacks += "release"
                buildConfigField("boolean", "UPDATER_ENABLED", "false")
                buildConfigField("boolean", "DEVELOPER_COPY_ENABLED", "true")
                buildConfigField("String", "DEVELOPER_COPY_CERT_SHA256", "\"${copyCertificate.lowercase()}\"")
                manifestPlaceholders["developerCopyCertificate"] = copyCertificate.lowercase()
            }
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
            initWith(getByName("release"))
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
            // Profile journeys run the fixture app, so collection needs no network or provider media.
            buildConfigField("boolean", "BENCHMARK_FIXTURES_ENABLED", "true")
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

    // CI's full `:app:lint` covers every source; skip lintVital inside each R8 assembly.
    lint {
        checkReleaseBuilds = false
    }

    // Controlled provider/container fixtures are shared by real-screen tests and benchmarks.
    // They are never compiled into the production or release-acceptance APK.
    sourceSets {
        getByName("debug").kotlin.directories.add("src/fixtures/java")
        getByName("debug").res.directories.add("src/fixtures/res")
        getByName("benchmarkRelease").kotlin.directories.add("src/fixtures/java")
        getByName("benchmarkRelease").res.directories.add("src/fixtures/res")
        getByName("nonMinifiedRelease").kotlin.directories.add("src/fixtures/java")
        getByName("nonMinifiedRelease").kotlin.directories.add("src/benchmarkRelease/java")
        getByName("nonMinifiedRelease").res.directories.add("src/fixtures/res")
        getByName("nonMinifiedRelease").res.directories.add("src/benchmarkRelease/res")
        getByName("debug").kotlin.directories.add("src/developerCopy/java")
        getByName("debug").res.directories.add("src/developerCopy/res")
        findByName("personalRelease")?.kotlin?.directories?.add("src/developerCopy/java")
        findByName("personalRelease")?.res?.directories?.add("src/developerCopy/res")
    }
}

val rejectPersonalReleaseInstall = tasks.register<RejectPersonalReleaseInstallTask>("rejectPersonalReleaseInstall")
val generateMonochromeLauncherIcon = tasks.register<GenerateMonochromeLauncherIconTask>(
    "generateMonochromeLauncherIcon",
) {
    sourceIcon.set(layout.projectDirectory.file("src/main/res/drawable-nodpi/theoria_app_icon.png"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res/monochromeLauncherIcon"))
}
tasks.matching {
    it.name.contains("PersonalRelease") &&
        (it.name.startsWith("install") || it.name.startsWith("uninstall") || it.name.startsWith("connected"))
}.configureEach {
    dependsOn(rejectPersonalReleaseInstall)
}

androidComponents {
    // Set only by the Release workflow, so every other build carries no private hosts.
    val linkHosts = parseLinkHosts(providers.environmentVariable("THEORIA_LINK_HOSTS").getOrElse(""))
    if (linkHosts.isNotEmpty()) {
        onVariants(selector().all()) { variant ->
            val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
            val linkHostsManifest = tasks.register<GenerateLinkHostsManifestTask>("generate${capitalizedVariant}LinkHostsManifest") {
                hosts.set(linkHosts)
                manifest.set(layout.buildDirectory.file("generated/linkHosts/${variant.name}/AndroidManifest.xml"))
            }
            variant.sources.manifests.addGeneratedManifestFile(linkHostsManifest, GenerateLinkHostsManifestTask::manifest)
        }
    }
    onVariants(selector().all()) { variant ->
        // Production-ID APKs include the signed personal update lane.
        if (variant.applicationId.get() != android.defaultConfig.applicationId) {
            variant.sources.res?.addGeneratedSourceDirectory(
                generateMonochromeLauncherIcon,
                GenerateMonochromeLauncherIconTask::outputDirectory,
            )
        }
    }
    // Baseline Profile otherwise synthesizes debug-signed production-ID derivatives of this
    // personal build. It is an export source only, never a measurement or connected-test lane.
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("benchmarkPersonalRelease", "nonMinifiedPersonalRelease")) {
            variant.enable = false
        }
    }
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
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        tasks.named("verifyBenchmarkFixtureArtifact").configure {
            // A required AGP-owned mapping proves this measurement lane actually ran R8.
            inputs.file(mapping).withPropertyName("benchmarkR8Mapping")
        }
    }

    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
    }

    onVariants(selector().withBuildType("release")) { variant ->
        val guard = tasks.register<VerifyPublicDeveloperCopyAbsentTask>("verifyPublicDeveloperCopyAbsent") {
            group = "verification"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(guard) }
    }
}

dependencies {
    implementation(project(":app-logic"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-data-android"))
    implementation(project(":core-sources"))
    implementation(project(":ui-kit"))
    implementation(project(":media-kit"))
    implementation(project(":feed-kit"))
    implementation(project(":feature-viewer"))
    implementation(project(":feature-search"))
    implementation(project(":feature-library"))
    implementation(project(":feature-discover"))
    implementation(project(":feature-series"))
    implementation(project(":feature-settings"))
    implementation(project(":vision-kit"))
    // Runs Mihon extensions as series sources; see mihon-host/README.md for what it costs release builds.
    implementation(project(":mihon-host"))

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
    implementation(libs.androidx.vectordrawable.animated)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.android.animation.webp) {
        exclude(group = "androidx.appcompat", module = "appcompat")
    }
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)

    baselineProfile(project(":baseline-profile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(project(":core-stubs"))
    testImplementation(testFixtures(project(":core-data")))
    testImplementation(testFixtures(project(":ui-kit")))
    testImplementation(testFixtures(project(":feed-kit")))
    testImplementation(testFixtures(project(":feature-library")))
    testImplementation(testFixtures(project(":feature-settings")))
    testImplementation(testFixtures(project(":feature-viewer")))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // Journeys run the fixture app, whose own classes never ship in a release.
    filter {
        exclude("com.theoriacodex.app.benchmark.**")
        exclude("com.theoriacodex.app.fixtures.**")
    }
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

// Reference screenshots are CI's Linux renders: other hosts draw text differently, so their captures stay in build/
// and can never overwrite a reference. Failed comparisons always land in build/. See scripts/screenshots.sh.
roborazzi {
    if (System.getProperty("os.name") == "Linux") outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
    compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi-compare")) }
}
