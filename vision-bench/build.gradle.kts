// Phase 0 of the visual-similarity plan: a standalone app that times WD-ViT and DINOv2 on LiteRT
// (CPU, GPU, NPU) on a physical phone. Its own application id keeps it away from every product
// package; it ships in no release. Driven by tools/phone-bench/bench.py.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.theoriacodex.visionbench"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.theoriacodex.visionbench"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // LiteRT finds its GPU accelerator and Qualcomm dispatch/compiler libraries by directory, and
    // the DSP loads QNN skels from disk, so native libraries must be extracted to nativeLibraryDir.
    packaging { jniLibs.useLegacyPackaging = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.qnn.runtime)
}
