// The Crema Android app. It began as the Phase-0 proof-of-concept that proved
// the cargo-ndk -> .so -> UniFFI Kotlin -> Compose -> live BLE path against a
// real DE1, and is now the real Android shell.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "crema-android"
include(":app")
