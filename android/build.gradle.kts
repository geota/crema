// Root build script. Plugin versions only; no configuration here.
//
// Toolchain: Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.4.0.
// AGP 9 has built-in Kotlin support, so the `org.jetbrains.kotlin.android`
// plugin is intentionally NOT declared here — AGP applies Kotlin itself. The
// Kotlin version IS these plugin versions (the Compose compiler is lock-stepped
// with Kotlin); keep them equal and bump together.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("net.mullvad.rust-android") version "0.10.1" apply false
}
