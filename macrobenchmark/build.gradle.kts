plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.theoriacodex.macrobenchmark"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "Macrobenchmark"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
}

val verifyMacrobenchmarkRunnerArtifact = tasks.register<Exec>("verifyMacrobenchmarkRunnerArtifact") {
    group = "verification"
    description = "Verifies that the packaged Macrobenchmark runner has no side-effect listener config."
    dependsOn("packageBenchmarkRelease")
    val runnerApk = layout.buildDirectory.file(
        "outputs/apk/benchmarkRelease/macrobenchmark-benchmarkRelease.apk",
    )
    val verifierScript = rootProject.layout.projectDirectory.file(
        "scripts/verify_macrobenchmark_runner_apk.py",
    )
    val analyzer = androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve(
        "cmdline-tools/latest/bin/apkanalyzer",
    )
    inputs.file(runnerApk)
    inputs.file(verifierScript)
    commandLine(
        "python3",
        verifierScript.asFile,
        runnerApk.get().asFile,
        analyzer,
    )
}

tasks.matching { task -> task.name == "assembleBenchmarkRelease" }.configureEach {
    finalizedBy(verifyMacrobenchmarkRunnerArtifact)
}

tasks.matching { task -> task.name == "connectedBenchmarkReleaseAndroidTest" }.configureEach {
    dependsOn(":app:verifyBenchmarkReleaseInstallableApplicationId")
}
