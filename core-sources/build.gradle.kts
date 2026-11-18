plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    compileOnly(libs.jspecify)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Release builds run without coroutine stack-trace recovery, which copies an exception each time it crosses
    // the dispatcher switch every source entry point makes. Tests match release so exception identity holds.
    systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
}

tasks.register<JavaExec>("providerHealthCheck") {
    group = "verification"
    description = "Runs opt-in live provider health checks and writes build/reports/provider-health/provider-health.json."
    dependsOn("classes")
    mainClass.set("com.theoriacodex.sources.health.ProviderHealthCheckCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty(
        "theoria.liveProviders",
        providers.gradleProperty("theoria.liveProviders").orElse("false").get(),
    )
    systemProperty(
        "theoria.liveSources.strict",
        providers.gradleProperty("theoria.liveSources.strict").orElse("false").get(),
    )
    systemProperty(
        "theoria.providerHealth.publicSafe",
        providers.gradleProperty("theoria.providerHealth.publicSafe").orElse("false").get(),
    )
    providers.gradleProperty("theoria.liveSources.sources").orNull?.let { sources ->
        systemProperty("theoria.liveSources.sources", sources)
    }
    providers.gradleProperty("theoria.providerProbeCases").orNull?.let { caseFile ->
        systemProperty("theoria.providerProbeCases", caseFile)
    }
    args(layout.buildDirectory.file("reports/provider-health/provider-health.json").get().asFile.absolutePath)
}
