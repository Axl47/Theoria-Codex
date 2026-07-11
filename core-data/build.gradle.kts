plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
