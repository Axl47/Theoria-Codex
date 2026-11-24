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
        // Mihon's 16 KB-aligned build of QuickJS (same `app.cash.quickjs` API as 0.9.2), published only on JitPack.
        // Nothing else resolves from there.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.zhanghai.quickjs-java") }
        }
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
include(":extension-host")
include(":ui-kit")
include(":media-kit")
include(":feed-kit")
include(":feature-viewer")
include(":feature-search")
include(":feature-library")
include(":feature-discover")
include(":feature-series")
include(":feature-settings")
include(":vision-kit")
include(":detekt-rules")
