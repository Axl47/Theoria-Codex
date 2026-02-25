plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.theoriacodex.app"
    compileSdk = 35

    val versionCodeOverride = providers.gradleProperty("theoria.versionCode")
        .orNull
        ?.toIntOrNull()
    val versionNameOverride = providers.gradleProperty("theoria.versionName").orNull

    defaultConfig {
        applicationId = "com.theoriacodex"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.1.12"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-sources"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
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
