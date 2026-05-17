import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Builds the de1-ffi Rust crate into per-ABI .so files via cargo-ndk and
    // packages them into the APK's jniLibs. See the `cargo` block below.
    id("org.mozilla.rust-android-gradle.rust-android")
}

android {
    namespace = "coffee.crema"
    compileSdk = 36

    defaultConfig {
        applicationId = "coffee.crema"
        // Teclast P25T tablet runs Android 12 (API 31); also the phone floor.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // The generated UniFFI bindings land in build/generated/uniffi; add that
    // tree to the main source set so they compile as ordinary Kotlin.
    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // UniFFI's generated Kotlin depends on JNA for the FFI calls and on
    // kotlinx-coroutines (its runtime helpers reference it).
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The de1-app `CoreOutput` JSON is deserialized with kotlinx.serialization.
    // The generated `core/bindings/crema-core.kt` types are @Serializable.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// ---------------------------------------------------------------------------
// Rust / NDK integration — org.mozilla.rust-android-gradle
// ---------------------------------------------------------------------------
// This compiles the `de1-ffi` cdylib for the listed Android ABIs and copies the
// resulting `libde1_ffi.so` into jniLibs so it ships in the APK. The plugin
// shells out to `cargo` + the NDK linker; `cargo-ndk` itself is not required
// when using this plugin, but the NDK and the Rust target must be installed
// (see android/README.md).
cargo {
    // Path from this module to the Rust workspace member.
    module = "../../core/de1-ffi"
    libname = "de1_ffi"
    // Teclast P25T and the Pixel phone are both arm64. arm64-v8a is the only
    // ABI needed for real hardware; add "x86_64" here to also run on an emulator.
    targets = listOf("arm64")
    // "debug" keeps the build fast; switch to "release" for shippable APKs.
    profile = "debug"
    // The crate is part of a Cargo workspace; build just this package.
    extraCargoBuildArguments = listOf("--package", "de1-ffi")
}

// Generate the UniFFI Kotlin bindings from the built cdylib.
//
// `de1-ffi` ships a `uniffi-bindgen` binary (src/bin/uniffi-bindgen.rs). We run
// it against the freshly built host-side .so/.dylib — UniFFI reads the exported
// metadata from any build of the library, host or target, so we point it at the
// arm64 artifact the cargo task just produced.
val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")

val generateUniffiBindings by tasks.registering(Exec::class) {
    description = "Generate UniFFI Kotlin bindings for de1-ffi."
    group = "rust"

    val crateDir = file("../../core/de1-ffi")
    val workspaceDir = file("../../core")
    // The arm64 cdylib produced by the `cargoBuild` task.
    val soFile = file("../../core/target/aarch64-linux-android/debug/libde1_ffi.so")

    inputs.dir(file("../../core/de1-ffi/src"))
    outputs.dir(uniffiOutDir)

    doFirst { uniffiOutDir.get().asFile.mkdirs() }

    workingDir = workspaceDir
    commandLine(
        "cargo", "run", "--package", "de1-ffi", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", soFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.get().asFile.absolutePath,
    )
}

// Wire the tasks: cargoBuild (from the plugin) -> generate bindings -> compile.
tasks.matching { it.name == "cargoBuild" || it.name.startsWith("cargoBuild") }
    .configureEach { finalizedBy(generateUniffiBindings) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiBindings)
}

// Make the native build run before the APK is assembled.
tasks.named("preBuild") {
    dependsOn("cargoBuild")
}
