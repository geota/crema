// Spike B — Phase-0 proof-of-concept Android project for Crema.
// Throwaway spike: proves the cargo-ndk -> .so -> UniFFI Kotlin -> Compose -> live BLE
// path against a real DE1. NOT the real app.

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

rootProject.name = "Spike B"
include(":app")
