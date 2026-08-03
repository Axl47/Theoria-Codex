plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.theoriacodex.baselineprofile"
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
    dependsOn(":app:verifyNonMinifiedReleaseInstallableApplicationId")
}
