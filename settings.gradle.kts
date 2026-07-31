pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Theoria Codex"
include(":app")
include(":app-logic")
include(":baseline-profile")
include(":macrobenchmark")
include(":core-domain")
include(":core-data")
include(":core-data-android")
include(":core-stubs")
include(":core-sources")
