import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.detekt) apply false
}

dependencies {
    kover(project(":app"))
    kover(project(":core-domain"))
    kover(project(":core-data"))
    kover(project(":core-data-android"))
    kover(project(":core-sources"))
    kover(project(":core-stubs"))
}

kover {
    reports {
        total {
            xml {
                xmlFile.set(layout.buildDirectory.file("reports/kover/quality.xml"))
            }
            html {
                htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
            }
            verify {
                rule("aggregate line coverage floor") {
                    minBound(55)
                }
            }
        }
    }
}

subprojects {
    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline.set(rootProject.layout.projectDirectory.file("config/detekt/baseline-${project.name}.xml"))
            basePath.set(rootProject.layout.projectDirectory)
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            autoCorrect.set(false)
            ignoreFailures.set(false)
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget.set("17")
            reports {
                html.required.set(true)
                sarif.required.set(true)
                checkstyle.required.set(false)
                markdown.required.set(false)
            }
        }
    }
}
