import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.detekt) apply false
}

dependencies {
    kover(project(":app"))
    kover(project(":app-logic"))
    kover(project(":core-domain"))
    kover(project(":core-data"))
    kover(project(":core-data-android"))
    kover(project(":core-sources"))
    kover(project(":core-stubs"))
}

kover {
    reports {
        filters {
            excludes {
                packages("com.theoriacodex.app.fixtures", "com.theoriacodex.app.benchmark", "com.theoriacodex.app.acceptance")
            }
        }
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
            // Kotlin FIR/PSI analysis must not traverse the same source tree concurrently.
            parallel.set(false)
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

// AGP registers Java compilation after project evaluation; wire its public output once all
// variant tasks exist so typed analysis can resolve this module's BuildConfig and Room types.
gradle.projectsEvaluated {
    subprojects.filter { module ->
        module.plugins.hasPlugin("dev.detekt") &&
            (module.plugins.hasPlugin("com.android.application") || module.plugins.hasPlugin("com.android.library"))
    }.forEach { module ->
        val javaCompilation = module.tasks.named<JavaCompile>("compileDebugJavaWithJavac")
        module.tasks.named<Detekt>("detektDebug") {
            dependsOn(javaCompilation)
            classpath.from(javaCompilation.flatMap { it.destinationDirectory })
        }
    }
}
