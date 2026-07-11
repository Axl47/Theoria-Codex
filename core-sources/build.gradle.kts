plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)
    implementation(libs.jsoup)
    compileOnly(libs.jspecify)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
    providers.gradleProperty("theoria.liveSources.sources").orNull?.let { sources ->
        systemProperty("theoria.liveSources.sources", sources)
    }
    providers.gradleProperty("theoria.providerProbeCases").orNull?.let { caseFile ->
        systemProperty("theoria.providerProbeCases", caseFile)
    }
    args(layout.buildDirectory.file("reports/provider-health/provider-health.json").get().asFile.absolutePath)
}
