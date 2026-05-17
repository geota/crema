// Root build script. Plugin versions only; no configuration here.
//
// Toolchain: Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.2.20.
// AGP 9 has built-in Kotlin support, so the `org.jetbrains.kotlin.android`
// plugin is intentionally NOT declared here — AGP applies Kotlin itself.
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("net.mullvad.rust-android") version "0.10.1" apply false
}
