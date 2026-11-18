plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(project(":core-domain"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    // The Gson parity harness takes kotlinx serializers and runs both libraries, so callers need both.
    testFixturesApi(libs.gson)
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
