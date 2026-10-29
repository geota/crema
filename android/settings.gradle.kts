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

// Toolchain resolution: the unit tests run on a Java 21 JVM (see the Test
// task config in app/build.gradle.kts). Gradle uses a local JDK 21 when it
// detects one and otherwise downloads one through Foojay.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
