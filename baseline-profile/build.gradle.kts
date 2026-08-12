plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    // The test APK is installed beside the isolated target app. Its package must therefore
    // remain distinct from both that target and the protected production package.
    namespace = "com.theoriacodex.baselineprofile.test"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "BaselineProfile"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    // Generation intentionally uses the explicitly connected API 37 emulator/device. Keeping
    // this out of normal assembly avoids silently starting an emulator during release builds.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
}

tasks.matching { task -> task.name == "connectedNonMinifiedReleaseAndroidTest" }.configureEach {
    dependsOn(
        ":app:verifyNonMinifiedReleaseInstallableApplicationId",
        "verifyNonMinifiedReleaseTestApplicationId",
    )
}

abstract class VerifyBaselineProfileTestApplicationIdTask : DefaultTask() {
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
            "Expected baseline-profile test application ID ${expectedApplicationId.get()}, " +
                "but ${outputMetadata.get().asFile} declared $packagedApplicationIds"
        }
    }
}

tasks.register<VerifyBaselineProfileTestApplicationIdTask>(
    "verifyNonMinifiedReleaseTestApplicationId",
) {
    group = "verification"
    description = "Verifies the packaged baseline-profile test APK uses its isolated application ID."
    dependsOn("packageNonMinifiedRelease")
    outputMetadata.set(
        layout.buildDirectory.file("outputs/apk/nonMinifiedRelease/output-metadata.json"),
    )
    expectedApplicationId.set("com.theoriacodex.baselineprofile.test")
}
